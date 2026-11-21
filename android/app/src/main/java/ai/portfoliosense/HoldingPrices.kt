package ai.portfoliosense

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.io.File

private val india = ZoneId.of("Asia/Kolkata")
private val feedDay = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
private fun normal(value: String) = value.trim().uppercase(Locale.ROOT).replace(Regex("\\s+"), " ")
internal fun holdingId(h: JSONObject): String = normal(h.str("isin")).ifBlank {
    normal(h.str("instrument_type")) + "|" + normal(h.str("name"))
}

internal data class MarketPrice(val price: Double, val epoch: Long, val source: String, val daily: Boolean) {
    fun json() = JSONObject().put("price", price).put("epoch", epoch).put("source", source).put("daily", daily)
}
internal fun applyPrice(row: JSONObject, quote: JSONObject?): JSONObject {
    val price = quote?.number("price")?.takeIf { it > 0 } ?: return row
    val updated = JSONObject(row.toString()).put("quote", quote)
    row.number("quantity")?.takeIf { it >= 0 }?.let { updated.put("current_value", price * it) }
    return updated
}

internal data class Listing(val symbol: String, val series: String, val isin: String, val names: Set<String>)
internal fun listings(text: String, etf: Boolean): List<Listing> {
    // NSE quotes CSV fields containing commas, including some scheme names.
    fun fields(line: String): List<String> {
        val out = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        for (c in line) when {
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { out += field.toString().trim(); field.clear() }
            else -> field.append(c)
        }
        out += field.toString().trim()
        return out
    }
    return text.lineSequence().drop(1).mapNotNull {
        val col=fields(it)
        val isin=col.getOrNull(if(etf) 5 else 6)?.trim().orEmpty()
        if (!isin.matches(Regex("IN[A-Z0-9]{10}"))) null else Listing(col[0],if(etf) "EQ" else col[2],isin,
            (if(etf) listOf(col[0],col[1],col[2]) else listOf(col[0],col[1])).map(::normal).toSet())
    }.distinctBy { it.isin to it.symbol }.toList()
}

internal fun chartPrice(text:String, symbol:String):MarketPrice? {
    val meta=JSONObject(text).getJSONObject("chart").optJSONArray("result")?.optJSONObject(0)?.optJSONObject("meta") ?: return null
    if (meta.str("symbol")!=symbol || meta.str("currency")!="INR") return null
    val price=meta.number("regularMarketPrice")?.takeIf { it>0 } ?: return null
    val epoch=meta.optLong("regularMarketTime")
    return if(epoch>0) MarketPrice(price,epoch,"NSE via Yahoo Finance",false) else null
}

internal fun navPrices(text:String, rows:List<JSONObject>):Map<String,MarketPrice> {
    val requested=rows.map(::holdingId).toSet()
    val found=mutableMapOf<String,MarketPrice>()
    val ambiguous=mutableSetOf<String>()
    val codes=mutableMapOf<String,String>()
    val lines=text.lineSequence().iterator()
    if(!lines.hasNext()) return emptyMap()
    val header=lines.next().split(';')
    val nav=header.indexOf("Net Asset Value")
    val date=header.indexOf("Date")
    if(nav<0 || date<0) return emptyMap()
    for(line in lines) {
        val cols=line.split(';')
        if(cols.size<=maxOf(3,nav,date)) continue
        val keys=listOf(normal(cols[1]),normal(cols[2]),"MUTUAL_FUND|"+normal(cols[3]))
            .filter { it in requested }.distinct()
        if(keys.isEmpty()) continue
        val price=cols[nav].toDoubleOrNull()?.takeIf { it.isFinite() && it>0 } ?: continue
        val day=runCatching { LocalDate.parse(cols[date].trim(),feedDay) }.getOrNull() ?: continue
        for(key in keys) {
            if(codes.put(key,cols[0])?.let { it!=cols[0] }==true) ambiguous+=key
            found[key]=MarketPrice(price,day.atStartOfDay(india).toEpochSecond(),"AMFI NAV",true)
        }
    }
    return found-ambiguous
}

internal class HoldingPrices(private val cacheDir:File, private val client:OkHttpClient = OkHttpClient.Builder()
    .callTimeout(12,TimeUnit.SECONDS).build()) {
    private fun download(url:String):String = client.newCall(Request.Builder().url(url)
        .header("User-Agent","Mozilla/5.0 (Android) PortfolioSense/1.6").build()).execute().use {
        check(it.isSuccessful) { "Price feed HTTP ${it.code}" }
        it.body.string()
    }
    private fun cached(name:String,url:String,ttl:Long,prefix:String):String {
        val file=File(cacheDir,name)
        if(file.isFile && System.currentTimeMillis()-file.lastModified() < ttl) return file.readText()
        return try {
            val data=download(url)
            require(data.startsWith(prefix,true) && data.contains('\n'))
            val tmp=File.createTempFile("quote-index",null,cacheDir)
            try { tmp.writeText(data); tmp.renameTo(file) } finally { tmp.delete() }
            data
        } catch(e:Exception) { if(file.isFile) file.readText() else "" }
    }
    suspend fun load(rows:List<JSONObject>):Map<String,MarketPrice> = withContext(Dispatchers.IO) {
        val eq=async { listings(cached("nse-eq.csv","https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv",86400000,"SYMBOL,"),false) }
        val etf=async { listings(cached("nse-etf.csv","https://nsearchives.nseindia.com/content/equities/eq_etfseclist.csv",86400000,"Symbol,"),true) }
        val all=(eq.await()+etf.await()).distinctBy { it.isin to it.symbol }
        val targets=rows.distinctBy(::holdingId)
        val matches=targets.mapNotNull { h ->
            val id=h.str("isin")
            val found=if(id.isNotBlank()) all.filter { it.isin==normal(id) }
                else all.filter { normal(h.str("name")) in it.names }
            found.singleOrNull()?.let { holdingId(h) to it }
        }.toMap()
        val nav=async {
            val funds=targets.filter { it.str("instrument_type")=="mutual_fund" && holdingId(it) !in matches }
            if(funds.isEmpty()) emptyMap() else navPrices(cached("amfi-nav.txt","https://portal.amfiindia.com/spages/NAVAll.txt",3600000,"Scheme Code;"),funds)
        }
        val limit=Semaphore(4)
        val quotes=matches.map { (key, listing) -> async {
            val full=listing.symbol+".NS"
            val q=limit.withPermit {
                listOf("query1","query2").firstNotNullOfOrNull { host ->
                    runCatching {
                        val url="https://$host.finance.yahoo.com/v8/finance/chart/".toHttpUrl().newBuilder()
                            .addPathSegment(full).build()
                        chartPrice(download(url.toString()),full)
                    }.getOrNull()
                }
            }
            q?.let { key to it }
        }}.awaitAll().filterNotNull().toMap().toMutableMap()
        val today=LocalDate.now(india)
        val missing=matches.filterKeys { key -> quotes[key]?.let { Instant.ofEpochSecond(it.epoch).atZone(india).toLocalDate()<today } ?: true }
        if(missing.isNotEmpty()) {
            var day=LocalDate.now(india)
            for(attempt in 1..7) {
                val dayId=day.format(DateTimeFormatter.ofPattern("ddMMyyyy"))
                val url="https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_$dayId.csv"
                val csv=if(day.dayOfWeek.value<=5) runCatching { download(url) }.getOrNull() else null
                if(csv?.startsWith("SYMBOL,")==true) {
                    val bySymbol=csv.lineSequence().drop(1).map { line->line.split(',').map(String::trim) }
                        .filter { it.size>8 }.associateBy { it[0] to it[1] }
                    for((key, asset) in missing) {
                        val record=bySymbol[asset.symbol to asset.series] ?: continue
                        val close=record[8].toDoubleOrNull()?.takeIf { it.isFinite() && it>0 } ?: continue
                        val observed=runCatching { LocalDate.parse(record[2],feedDay) }.getOrNull() ?: continue
                        val previous=quotes[key]
                        if(previous==null || observed>Instant.ofEpochSecond(previous.epoch).atZone(india).toLocalDate())
                            quotes[key]=MarketPrice(close,observed.atTime(15,30).atZone(india).toEpochSecond(),"NSE published close",true)
                    }
                    break
                }
                day=day.minusDays(1)
            }
        }
        quotes+nav.await()
    }
}
