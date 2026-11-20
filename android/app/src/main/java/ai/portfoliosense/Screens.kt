package ai.portfoliosense

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

fun typeName(h: JSONObject) =
    when (h.str("instrument_type")) {
        "mutual_fund" -> "Mutual fund"
        "stock" -> "Stock"
        else -> h.str("instrument_type").ifBlank { "Other" }
    }

@Composable
private fun Summary(rows: List<JSONObject>) {
    fun total(key: String) =
        rows.mapNotNull { it.number(key) }.takeIf { it.size == rows.size && it.isNotEmpty() }?.sum()
    Panel("Portfolio totals") {
        val invested = total("invested_value")
        val current = total("current_value")
        Metric("Current value", money(current))
        Metric("Invested", money(invested))
        Metric(
            "Overall P&L",
            money(if (current != null && invested != null) current - invested else null),
        )
        Metric("Positions", rows.size.toString())
        Metric("Stocks", rows.count { typeName(it) == "Stock" }.toString())
        Metric("Mutual funds", rows.count { typeName(it) == "Mutual fund" }.toString())
    }
}

@Composable
fun Holdings(vm: PortfolioModel, query: String, setQuery: (String) -> Unit, upload: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("All") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    val sorts =
        listOf(
            "Name A–Z",
            "Current value ↓",
            "Current value ↑",
            "P&L ↓",
            "P&L ↑",
            "P&L % ↓",
            "Quantity ↓",
        )
    if(vm.result!=null) {
        Panel("Holding prices") {
            Text("Exchange quotes may be delayed. Mutual funds use their latest published NAV.")
            OutlinedButton(onClick=vm::refreshPrices,enabled=!vm.refreshingPrices) { Text(if(vm.refreshingPrices) "Refreshing…" else "Refresh prices") }
            if(vm.priceStatus.isNotBlank()) Text(vm.priceStatus,color=palette.onSurfaceVariant)
        }
    }
    Summary(vm.holdings)
    Button(upload, enabled = !vm.busy) { Text("Upload statements") }
    OutlinedTextField(
        query,
        setQuery,
        Modifier.fillMaxWidth(),
        label = { Text("Search name, ISIN or category") },
        singleLine = true,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = spacing) {
        listOf("All", "Stock", "Mutual fund").forEach {
            FilterChip(filter == it, { filter = it }, { Text(it) })
        }
    }
    Box {
        OutlinedButton({ open = true }) { Text("Sort · " + sorts[sort]) }
        DropdownMenu(open, { open = false }) {
            sorts.forEachIndexed { i, title ->
                DropdownMenuItem(
                    { Text(title) },
                    {
                        sort = i
                        open = false
                    },
                )
            }
        }
    }
    val matches =
        vm.holdings.filter { h ->
            (filter == "All" || typeName(h) == filter) &&
                listOf("name", "isin", "category").any { h.str(it).contains(query.trim(), true) }
        }
    val rows =
        when (sort) {
            1 -> matches.sortedByDescending { it.number("current_value") }
            2 -> matches.sortedBy { it.number("current_value") ?: Double.POSITIVE_INFINITY }
            3 -> matches.sortedByDescending { it.pnl() }
            4 -> matches.sortedBy { it.pnl() ?: Double.POSITIVE_INFINITY }
            5 ->
                matches.sortedByDescending { h ->
                    h.number("invested_value")?.takeIf { it > 0 }?.let { h.pnl()?.div(it) }
                }
            6 -> matches.sortedByDescending { it.number("quantity") }
            else -> matches.sortedBy { it.str("name").lowercase() }
        }
    if (rows.isEmpty()) Text("No matching holdings.")
    rows.forEach { h ->
        Panel(h.str("name")) {
            Text(
                listOf(typeName(h), h.str("category"), h.str("isin"))
                    .filter(String::isNotBlank)
                    .joinToString(" · "),
                color = palette.primary,
            )
            Metric("Quantity", h.number("quantity")?.toString() ?: "—")
            val quote=h.optJSONObject("quote")
            val qty=h.number("quantity")
            if(quote!=null) {
                val daily=quote.optBoolean("daily")
                Metric(if(quote.str("source")=="AMFI NAV") "Latest NAV" else "Latest price",money(quote.number("price")))
                val stamp=java.time.Instant.ofEpochSecond(quote.getLong("epoch")).atZone(java.time.ZoneId.of("Asia/Kolkata"))
                val fmt=java.time.format.DateTimeFormatter.ofPattern(if(daily) "dd MMM yyyy" else "dd MMM yyyy, HH:mm 'IST'", java.util.Locale.ENGLISH)
                Text(quote.str("source")+" · "+stamp.format(fmt),color=palette.onSurfaceVariant)
            } else if(qty!=null && qty>0 && h.number("current_value")!=null) {
                Metric("Statement price",money(h.getDouble("current_value")/qty))
            }
            Metric(if(quote!=null && qty!=null && qty>=0) "Market value" else "Statement value", money(h.number("current_value")))
            Metric("Invested", money(h.number("invested_value")))
            Metric("P&L", money(h.pnl()))
            val pct =
                h.number("invested_value")?.takeIf { it > 0 }?.let { h.pnl()?.times(100)?.div(it) }
            Metric("Return", pct?.let { moneyFormat.format(it) + "%" } ?: "—")
            if (h.str("source").isNotBlank()) Text(h.str("source"))
        }
    }
}

@Composable
fun Markets(vm: PortfolioModel) {
    Text("Illustrative market snapshot", color = palette.primary)
    val m = vm.markets
    Panel("Market indices") {
        m.rows("indices").forEach {
            Metric(it.str("name"), it.str("value") + "   " + it.str("chg"))
        }
    }
    Panel("Sector performance") {
        val s = m.getJSONObject("sectors")
        Bars(s.strings("labels"), s.strings("data").map(String::toDouble))
    }
    listOf("gainers" to "Top gainers", "losers" to "Top losers").forEach { (key, title) ->
        Panel(title) {
            m.rows(key).forEach { Metric(it.str("name"), it.str("price") + "   " + it.str("chg")) }
        }
    }
    Panel("Institutional flows") {
        Text("Net flows in ₹ crore", color = palette.onSurfaceVariant)
        val f = m.getJSONObject("fii")
        f.strings("months").forEachIndexed { i, month ->
            Metric(
                month,
                "FII " +
                    f.getJSONArray("fii").getInt(i) +
                    "   ·   DII " +
                    f.getJSONArray("dii").getInt(i),
            )
        }
    }
}

private fun relevant(vm: PortfolioModel): Set<String> {
    val keys = linkedSetOf<String>()
    val h = vm.holdings
    val flags = vm.result?.strings("risk_flags").orEmpty().joinToString(" ").lowercase()
    if (h.any { typeName(it) == "Mutual fund" }) keys.addAll(listOf("sip", "elss", "etf"))
    if (h.any { (it.pnl() ?: 0.0) < 0 } || vm.score < 50) keys.addAll(listOf("pnl", "rebalancing"))
    if (Regex("concentrat|overweight|heavy|sector").containsMatchIn(flags))
        keys.add("diversification")
    if ("rebalanc" in flags) keys.add("rebalancing")
    val names = h.joinToString { it.str("name").uppercase() }
    if (Regex("NTPC|NHPC|ONGC|SBI|BEL|NALCO|PSU").containsMatchIn(names)) keys.add("psu")
    if (Regex("GOLD|SGB").containsMatchIn(names)) keys.add("gold")
    if (h.size in 1..4) keys.add("diversification")
    return keys.ifEmpty { setOf("diversification", "pnl", "rebalancing") }.take(5).toSet()
}

@Composable
fun Learn(vm: PortfolioModel) {
    var recommended by rememberSaveable { mutableStateOf(false) }
    Row(horizontalArrangement = spacing) {
        FilterChip(!recommended, { recommended = false }, { Text("All lessons") })
        FilterChip(recommended, { recommended = true }, { Text("For your portfolio") })
    }
    val topics = relevant(vm)
    vm.lessons
        .filter { !recommended || it.str("key") in topics }
        .forEach { lesson ->
            key(lesson.str("key")) {
                var expanded by rememberSaveable { mutableStateOf(false) }
                Panel(lesson.str("title")) {
                    Text(lesson.str("summary"), color = palette.onSurfaceVariant)
                    if (expanded) Text(lesson.str("details"))
                    TextButton({ expanded = !expanded }) {
                        Text(if (expanded) "Show less" else "Read lesson")
                    }
                }
            }
        }
}

@Composable
fun History(vm: PortfolioModel) {
    Panel("Statement history") {
        if (vm.history.isEmpty()) Text("No uploads yet", color = palette.onSurfaceVariant)
        vm.history.forEach { entry ->
            Text(entry.name)
            Metric(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(entry.time)),
                entry.state,
            )
        }
    }
}

@Composable
fun Account(vm: PortfolioModel, configure: () -> Unit) {
    Panel("Analysis service") {
        Text(if (vm.useNvidia) "Built-in analyzer · NVIDIA Nemotron" else vm.endpoint)
        OutlinedButton(configure, enabled = !vm.busy) { Text("Configure analyzer") }
        Metric("AI provider", vm.result?.str("provider") ?: "Sample")
        Metric("Recent uploads", vm.history.size.toString())
    }
    Panel("Investor profile") {
        Text(if (vm.result == null) "Upamanyu Samal · Sample profile" else "Retail investor")
        Metric("Holdings tracked", vm.holdings.size.toString())
        Metric("Last upload", vm.history.firstOrNull()?.name ?: "—")
    }
    Summary(vm.holdings)
    History(vm)
    Panel("Data and privacy") {
        Text(
            if (vm.useNvidia)
                "Your device extracts statement text and masks common PAN, email and numeric ID patterns before sending it to NVIDIA. Other identifying details may remain."
            else "Selected statements are sent over HTTPS to your configured PortfolioSense server."
        )
        Text("Your latest report and recent upload history are saved on this device.")
    }
}
