package ai.portfoliosense

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.NumberFormat
import java.util.Locale
import org.json.JSONObject

val palette =
    darkColorScheme(
        primary = Color(0xFFD0B2FF),
        onPrimary = Color(0xFF301753),
        secondary = Color(0xFFB9A8DE),
        background = Color(0xFF100E15),
        surface = Color(0xFF19161F),
        surfaceContainer = Color(0xFF201C29),
        primaryContainer = Color(0xFF493466),
        onSurfaceVariant = Color(0xFFC5BECE),
        outlineVariant = Color(0xFF3D3548),
    )
private val tabs = listOf("Dashboard", "Holdings", "Markets", "Learn", "Account")
private val icons =
    listOf(
        Icons.Default.Home,
        Icons.Default.List,
        Icons.Default.DateRange,
        Icons.Default.Info,
        Icons.Default.Person,
    )
val spacing = Arrangement.spacedBy(10.dp)
val moneyFormat: NumberFormat
    get() =
        NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).apply {
            maximumFractionDigits = 2
        }

fun money(value: Double?) = value?.let { "₹" + moneyFormat.format(it) } ?: "—"

fun JSONObject.pnl(): Double? =
    number("current_value")?.let { cur -> number("invested_value")?.let { cur - it } }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = palette) { PortfolioApp(viewModel()) } }
    }
}

@Composable
fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = spacing) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), color = palette.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Heading(title: String) =
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

@Composable
fun Bars(labels: List<String>, values: List<Double>, unit: String = "%") {
    val max =
        if (unit == "%") maxOf(100.0, values.maxOfOrNull { kotlin.math.abs(it) } ?: 1.0)
        else values.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1.0) ?: 1.0
    labels.zip(values).forEach { (label, value) ->
        Metric(label, moneyFormat.format(value) + unit)
        LinearProgressIndicator(
            progress = { (kotlin.math.abs(value) / max).toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = if (value < 0) palette.error else palette.primary,
        )
    }
}

@Composable
fun PortfolioApp(vm: PortfolioModel) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(owner, vm) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) { vm.refreshPrices(); kotlinx.coroutines.delay(60_000L) }
        }
    }
    var page by rememberSaveable { mutableStateOf("Dashboard") }
    var config by rememberSaveable { mutableStateOf(false) }
    var pickAfterConfig by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            vm.analyze(it)
        }
    val pick = {
        launcher.launch(
            arrayOf(
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            )
        )
    }
    val upload = {
        if (!vm.configured) {
            pickAfterConfig = true
            config = true
        } else pick()
    }
    BackHandler(page != "Dashboard") { page = "Dashboard" }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = palette.surface) {
                tabs.forEachIndexed { i, name ->
                    NavigationBarItem(
                        selected = page == name,
                        onClick = { page = name },
                        icon = { Icon(icons[i], null) },
                        label = { Text(name, fontSize = 10.sp, maxLines = 1) },
                    )
                }
            }
        }
    ) { pad ->
        key(page) {
            Column(
                Modifier.fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = spacing,
                ) {
                    Surface(shape = RoundedCornerShape(9.dp), color = palette.primaryContainer) {
                        Icon(
                            Icons.Default.AccountBox,
                            null,
                            Modifier.padding(6.dp).size(20.dp),
                            tint = palette.primary,
                        )
                    }
                    Text("PortfolioSense AI", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(
                        onClick = {
                            page = "Holdings"
                            query = ""
                        }
                    ) {
                        Icon(Icons.Default.Search, "Search holdings")
                    }
                }
                Text(
                    if (page == "Dashboard") "Portfolio overview" else page,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (page in listOf("Dashboard", "Holdings", "Account") && vm.result == null)
                    Text(
                        "Sample portfolio",
                        color = palette.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                if (vm.message.isNotBlank()) {
                    Text(vm.message, color = palette.secondary)
                    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                when (page) {
                    "Dashboard" -> Dashboard(vm, upload, { page = "Holdings" })
                    "Holdings" -> Holdings(vm, query, { query = it }, upload)
                    "Markets" -> Markets(vm)
                    "Learn" -> Learn(vm)
                    else ->
                        Account(vm) {
                            pickAfterConfig = false
                            config = true
                        }
                }
            }
        }
    }
    if (config) {
        var native by rememberSaveable { mutableStateOf(vm.useNvidia) }
        var url by rememberSaveable { mutableStateOf(vm.endpoint) }
        var error by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { config = false },
            title = { Text("Portfolio analyzer") },
            text = {
                Column(verticalArrangement = spacing) {
                    Row(horizontalArrangement = spacing) {
                        FilterChip(
                            native,
                            {
                                native = true
                                error = false
                            },
                            { Text("NVIDIA") },
                        )
                        FilterChip(
                            !native,
                            {
                                native = false
                                error = false
                            },
                            { Text("Custom server") },
                        )
                    }
                    if (native) {
                        Text(
                            "PortfolioSense reads your statements on this device and sends extracted text to NVIDIA Nemotron for analysis."
                        )
                    } else {
                        Text("Selected documents will be sent to your PortfolioSense server.")
                        OutlinedTextField(
                            url,
                            {
                                url = it
                                error = false
                            },
                            label = { Text("HTTPS server URL") },
                            singleLine = true,
                            isError = error,
                        )
                        if (error)
                            Text("Enter a valid HTTPS server address.", color = palette.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (if (native) vm.selectNvidia() else vm.setEndpoint(url)) {
                            config = false
                            if (pickAfterConfig) pick()
                        } else error = true
                    }
                ) {
                    Text(if (pickAfterConfig) "Save and choose files" else "Save")
                }
            },
            dismissButton = { TextButton(onClick = { config = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Dashboard(vm: PortfolioModel, upload: () -> Unit, showHoldings: () -> Unit) {
    val report = vm.result
    val score = vm.score
    val confidence = report?.optInt("confidence_score") ?: vm.sample.getInt("confidenceScore")
    Column(
        Modifier.fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(Color(0xFF49316A), Color(0xFF241D30))),
                RoundedCornerShape(24.dp),
            )
            .padding(22.dp),
        verticalArrangement = spacing,
    ) {
        Text("Portfolio health", style = MaterialTheme.typography.titleMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { score / 100f },
                    modifier = Modifier.size(88.dp),
                    strokeWidth = 6.dp,
                    color = palette.primary,
                    trackColor = palette.outlineVariant,
                )
                Text(score.toString(), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            Column {
                Text(
                    when {
                        score >= 70 -> "Optimized"
                        score >= 40 -> "Moderate"
                        else -> "Needs attention"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text("AI confidence  $confidence%", color = palette.onSurfaceVariant)
            }
        }
        Button(upload, Modifier.fillMaxWidth(), enabled = !vm.busy) { Text("Upload statements") }
        Text(
            "PDF, XLSX or DOCX",
            style = MaterialTheme.typography.labelSmall,
            color = palette.onSurfaceVariant,
        )
    }
    QuantPanel(vm)
    var showStatementDetails by rememberSaveable { mutableStateOf(false) }
    if (report != null) TextButton({ showStatementDetails = !showStatementDetails }) {
        Text(if(showStatementDetails) "Hide statement reconciliation" else "View statement reconciliation")
    }
    if(report == null || showStatementDetails) Panel(if(report == null) "Strategy insight" else "Statement reconciliation") {
        report?.str("portfolio_diagnosis")?.takeIf(String::isNotBlank)?.let { Text(it) }
        Text(
            report?.str("suggested_move") ?: vm.sample.str("suggestion"),
            style = MaterialTheme.typography.titleMedium,
            color = palette.primary,
        )
        Text(report?.str("logic_breakdown") ?: vm.sample.str("confBasis"))
        report?.strings("data_verifier")?.forEach { Text("•  $it") }
        if (vm.lastFiles.isNotEmpty())
            TextButton({ vm.analyze(vm.lastFiles) }, enabled = !vm.busy) { Text("Analyze again") }
    }
    Panel("Asset allocation") {
        if (report == null)
            Bars(
                vm.sample.getJSONObject("allocation").strings("labels"),
                listOf(25.0, 18.0, 15.0, 42.0),
            )
        else {
            val grouped =
                vm.holdings
                    .filter { (it.number("current_value") ?: 0.0) > 0 }
                    .groupBy { it.str("category").ifBlank { typeName(it) } }
                    .mapValues { (_, list) -> list.sumOf { it.getDouble("current_value") } }
            if (grouped.isEmpty()) Text("Allocation requires holdings with current values.")
            else Bars(grouped.keys.toList(), grouped.values.map { it * 100 / grouped.values.sum() })
        }
    }
    Panel("Asset mix") {
        if (report == null)
            Bars(listOf("Equity", "Debt", "Hybrid", "REIT"), listOf(52.0, 28.0, 12.0, 8.0))
        else {
            val counts = vm.holdings.groupingBy(::typeName).eachCount()
            if (counts.isEmpty()) Text("No instrument types available.")
            else {
                Text("Share of holdings by instrument count")
                Bars(counts.keys.toList(), counts.values.map { it * 100.0 / vm.holdings.size })
            }
        }
    }
    Panel("Market pulse") {
        val bull = if (report == null) 60 else Math.round(score * .8).toInt()
        val bear = if (report == null) 10 else Math.round((100 - score) * .25).toInt()
        Bars(
            listOf("Bullish", "Neutral", "Bearish"),
            listOf(bull, 100 - bull - bear, bear).map(Int::toDouble),
        )
    }
    val flags = report?.strings("risk_flags").orEmpty()
    if (flags.isNotEmpty()) Panel("Risk flags") { flags.forEach { Text("•  $it") } }
    Panel("Trade signals") {
        val actions = report?.rows("top_actions") ?: vm.sample.rows("recommendations")
        actions.forEach {
            Metric(
                it.str("ticker"),
                listOf(it.str("action"), it.str("priority"))
                    .filter(String::isNotBlank)
                    .joinToString(" · "),
            )
            Text(it.str("reason"))
            HorizontalDivider()
        }
        if (actions.isEmpty() && report?.str("provider") == "NVIDIA Nemotron")
            Text("No trade signals supported by this statement.")
        else if (actions.isEmpty()) {
            val tickers =
                report
                    ?.strings("tickers")
                    .orEmpty()
                    .ifEmpty {
                        vm.holdings
                            .map {
                                it.str("name")
                                    .substringBefore(' ')
                                    .uppercase()
                                    .filter(Char::isLetterOrDigit)
                            }
                            .filter(String::isNotEmpty)
                    }
                    .take(6)
            if (tickers.isEmpty()) Text("No trade signals returned.")
            else {
                Text("Illustrative ticker signals", color = palette.primary)
                tickers.forEachIndexed { index, ticker ->
                    val buy = (score >= 55) == (index % 3 != 1)
                    Metric(ticker, if (buy) "BUY" else "SELL")
                    Text(if (index % 2 == 0) "Sector trend" else "Overvalued")
                }
            }
        }
    }
    Panel("Holdings") {
        vm.holdings.take(8).forEach { Metric(it.str("name"), money(it.number("current_value"))) }
        TextButton(showHoldings) { Text("View all holdings") }
    }
    History(vm)
}
