package ai.portfoliosense

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject

fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

fun JSONObject.rows(key: String) = optJSONArray(key)?.objects().orEmpty()

fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key)

fun JSONObject.number(key: String) = optDouble(key).takeIf { it.isFinite() }

fun JSONObject.strings(key: String): List<String> {
    val a = optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).map { a.getString(it) }
}

data class ActivityEntry(val name: String, val state: String, val time: Long)

class PortfolioModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("portfolio", 0)
    var useNvidia by
        mutableStateOf(
            prefs.getBoolean("useNvidia", prefs.getString("endpoint", "").isNullOrEmpty())
        )
        private set

    val configured
        get() = useNvidia || endpoint.isNotBlank()

    fun selectNvidia(): Boolean {
        useNvidia = true
        prefs.edit().putBoolean("useNvidia", true).apply()
        return true
    }

    var plan by mutableStateOf(prefs.getString("quantPlan",null)?.let(::JSONObject))
        private set

    private fun savePlan(value:JSONObject) {
        plan=value
        prefs.edit().putString("quantPlan",value.toString()).apply()
    }

    fun importPlan(uri:Uri) {
        if(busy) return
        busy=true
        message="Calculating net trade outcomes…"
        viewModelScope.launch {
            try {
                val data=withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)!!.bufferedReader().use { reader ->
                        val buf=CharArray(4096)
                        val text=StringBuilder()
                        while(true) { val n=reader.read(buf); if(n<0) break; text.append(buf,0,n); require(text.length<=2000000) { "Scenario exceeds 2 MB" } }
                        JSONObject(text.toString())
                    }
                }
                val calculated=QuantRuntime.run(app,data)
                calculated.put("source","Imported scenario · "+data.optString("as_of","undated"))
                savePlan(calculated)
                message=if(calculated.str("status")=="ready") "Net trade plan calculated" else "Review plan diagnostics"
            } catch(e:Exception) { message="Scenario could not be calculated: "+e.localizedMessage }
            finally { busy=false }
        }
    }

    fun reviewPlan() {
        val original=plan ?: return
        if(busy || (original.str("status")!="ready" && !original.has("total_value"))) return
        busy=true
        message="Reviewing the calculated plan with NVIDIA…"
        viewModelScope.launch {
            try {
                val note=withContext(Dispatchers.IO) { NvidiaAnalyzer.review(client,app.getString(R.string.nvidia_api_key),original) }
                savePlan(JSONObject(original.toString()).put("review",note))
                message="Review complete"
            } catch(e:Exception) {message="Review unavailable: "+e.localizedMessage}
            finally {busy=false}
        }
    }

    private suspend fun finish(report: JSONObject) {
        val diagnostics = statementInsight(report)
        prefs.edit().putString("result", report.toString()).putString("quantPlan", diagnostics.toString()).apply()
        result = report
        plan = diagnostics
        refreshPrices()
        update("Complete")
        message = "Analysis complete"
        if (useNvidia && diagnostics.has("total_value")) {
            message = "Reviewing concentration and costs…"
            try {
                val review = withContext(Dispatchers.IO) {
                    NvidiaAnalyzer.review(client, app.getString(R.string.nvidia_api_key), diagnostics)
                }
                savePlan(JSONObject(diagnostics.toString()).put("review", review))
                message = "Analysis complete"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "Statement analyzed. AI review unavailable. You can retry it below." }
        }
    }

    private val client =
        OkHttpClient.Builder().readTimeout(150, TimeUnit.SECONDS).followRedirects(false).build()

    private fun asset(name: String) = app.assets.open(name).bufferedReader().use { it.readText() }

    val sample = JSONObject(asset("sample.json"))
    val markets = JSONObject(asset("markets.json"))
    val lessons = JSONArray(asset("lessons.json")).objects()
    var result by mutableStateOf(prefs.getString("result", null)?.let(::JSONObject))
        private set

    var endpoint by mutableStateOf(prefs.getString("endpoint", "").orEmpty())
        private set

    var history by
        mutableStateOf(
            JSONArray(prefs.getString("history", "[]")).objects().map {
                ActivityEntry(it.getString("name"), it.getString("state"), it.getLong("time"))
            }
        )
        private set

    var busy by mutableStateOf(false)
        private set

    var message by mutableStateOf("")
        private set

    var lastFiles = emptyList<Uri>()
        private set

    private val prices = HoldingPrices(app.cacheDir)
    private var priceCache by mutableStateOf(JSONObject(prefs.getString("holdingQuotes", "{}") ?: "{}"))
    var priceStatus by mutableStateOf("")
        private set
    var refreshingPrices by mutableStateOf(false)
        private set
    private var priceJob: Job? = null
    private var pricedReport: JSONObject? = null

    fun refreshPrices() {
        val snapshot = result ?: return
        if (pricedReport === snapshot && priceJob?.isActive == true) return
        priceJob?.cancel()
        pricedReport = snapshot
        val rows = snapshot.rows("holdings")
        if (rows.isEmpty()) { refreshingPrices = false; priceStatus = ""; return }
        refreshingPrices = true
        priceStatus = "Refreshing prices…"
        priceJob = viewModelScope.launch {
            try {
                val fetched = prices.load(rows)
                if (result !== snapshot) return@launch
                val updated = JSONObject()
                for (row in rows) {
                    val id = holdingId(row)
                    val old = priceCache.optJSONObject(id)
                    val latest = fetched[id]
                    val keep = if (latest != null && latest.epoch >= (old?.optLong("epoch") ?: 0L))
                        latest.json() else old
                    if (keep != null) updated.put(id,keep)
                }
                priceCache = updated
                prefs.edit().putString("holdingQuotes",updated.toString()).apply()
                val count=rows.count { holdingId(it) in fetched }
                priceStatus = if(count==rows.size) "Prices checked for all ${rows.size} positions."
                    else "Prices available for $count of ${rows.size} positions. Other rows use their last saved values."
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { if(result === snapshot) priceStatus="Price feeds unavailable. Showing saved values." }
            finally { if(result === snapshot) refreshingPrices=false }
        }
    }

    val holdings: List<JSONObject>
        get() = result?.rows("holdings")?.map { applyPrice(it,priceCache.optJSONObject(holdingId(it))) } ?: sample.rows("holdings")

    val score
        get() = result?.optInt("sentiment_score") ?: sample.getInt("healthScore")

    init {
        result?.let { previous ->
            if ((plan == null || plan?.str("source") == "Latest statement") && plan?.has("statement_version") != true)
                savePlan(statementInsight(previous))
        }
        if (prefs.contains("nvidia.token"))
            prefs.edit().remove("nvidia.token").remove("nvidia.iv").apply()
        if (history.firstOrNull()?.state in listOf("Uploading", "Queued", "In progress"))
            update("Interrupted")
    }

    fun setEndpoint(value: String): Boolean {
        val url = value.trim().toHttpUrlOrNull() ?: return false
        if (
            !url.isHttps ||
                url.username.isNotEmpty() ||
                url.password.isNotEmpty() ||
                url.query != null ||
                url.fragment != null
        )
            return false
        endpoint = url.toString().trimEnd('/')
        useNvidia = false
        prefs.edit().putString("endpoint", endpoint).putBoolean("useNvidia", false).apply()
        return true
    }

    private fun persistHistory() {
        val data = JSONArray()
        history.forEach {
            data.put(JSONObject().put("name", it.name).put("state", it.state).put("time", it.time))
        }
        prefs.edit().putString("history", data.toString()).apply()
    }

    private fun update(status: String) {
        history =
            history.mapIndexed { index, entry ->
                if (index == 0) entry.copy(state = status) else entry
            }
        persistHistory()
    }

    private fun json(request: Request): JSONObject =
        client.newCall(request).execute().use {
            val body = it.body.string()
            if (!it.isSuccessful)
                throw IOException(
                    runCatching { JSONObject(body).getString("detail") }
                        .getOrDefault("Server returned HTTP ${it.code}")
                )
            JSONObject(body)
        }

    fun analyze(uris: List<Uri>) {
        if (busy || uris.isEmpty() || !configured) return
        val native = useNvidia
        val base = endpoint
        busy = true
        message = "Preparing statements…"
        viewModelScope.launch {
            var added = false
            try {
                val files =
                    withContext(Dispatchers.IO) {
                        uris.map { uri ->
                            val resolver = app.contentResolver
                            val name =
                                resolver
                                    .query(
                                        uri,
                                        arrayOf(OpenableColumns.DISPLAY_NAME),
                                        null,
                                        null,
                                        null,
                                    )
                                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                                    ?: throw IOException("Unable to read the file name")
                            require(
                                name.substringAfterLast('.').lowercase() in
                                    setOf("pdf", "xlsx", "docx")
                            ) {
                                "Choose a PDF, XLSX or DOCX statement."
                            }
                            val body =
                                object : RequestBody() {
                                    override fun contentType() =
                                        resolver.getType(uri)?.toMediaTypeOrNull()

                                    override fun writeTo(sink: BufferedSink) {
                                        val stream =
                                            resolver.openInputStream(uri)
                                                ?: throw IOException("Unable to open $name")
                                        stream.source().use { sink.writeAll(it) }
                                    }
                                }
                            name to body
                        }
                    }
                history =
                    (listOf(
                            ActivityEntry(
                                files.joinToString { it.first },
                                "Uploading",
                                System.currentTimeMillis(),
                            )
                        ) + history)
                        .take(8)
                persistHistory()
                added = true
                lastFiles = uris
                if (native) {
                    update("In progress")
                    message = "Reading statements on this device…"
                    val statement =
                        withContext(Dispatchers.IO) {
                            StatementReader.read(app, uris.zip(files.map { it.first }))
                        }
                    message = "Analyzing with NVIDIA Nemotron…"
                    val report =
                        withContext(Dispatchers.IO) {
                            NvidiaAnalyzer.analyze(
                                client,
                                app.getString(R.string.nvidia_api_key),
                                statement,
                            )
                        }
                    finish(report)
                    return@launch
                }
                message = "Uploading statements…"
                val job =
                    withContext(Dispatchers.IO) {
                        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                        files.forEach { (name, body) -> form.addFormDataPart("files", name, body) }
                        json(Request.Builder().url("$base/analyze").post(form.build()).build())
                            .getString("job_id")
                    }
                update("Queued")
                message = "Queued for analysis…"
                repeat(60) {
                    delay(2000)
                    val data =
                        withContext(Dispatchers.IO) {
                            json(Request.Builder().url("$base/jobs/$job").build())
                        }
                    when (data.getString("status")) {
                        "complete" -> {
                            val report = data.getJSONObject("result")
                            finish(report)
                            return@launch
                        }
                        "failed",
                        "not_found" ->
                            throw IOException(
                                data.str("error").ifBlank { "Analysis job unavailable" }
                            )
                        "in_progress" -> {
                            update("In progress")
                            message = "Analyzing statements…"
                        }
                    }
                }
                throw IOException("Analysis timed out. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (added) update("Failed")
                message = e.localizedMessage ?: "Unable to analyze statements."
            } finally {
                busy = false
            }
        }
    }
}
