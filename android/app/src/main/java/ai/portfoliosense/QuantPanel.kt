package ai.portfoliosense

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import org.json.JSONObject

private fun percent(x:Double) = String.format(Locale.US,"%.2f%%",100*x)
private fun label(k:String)=when {
    k=="__cash" -> "Cash"
    k.startsWith("__unallocated:") -> "Unallocated · "+k.substringAfter(':')
    else -> k
}

@Composable
internal fun QuantPanel(vm:PortfolioModel) {
    val plan=vm.plan
    val statement = plan?.has("statement_version") == true
    var advanced by rememberSaveable { mutableStateOf(false) }
    val context=LocalContext.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if(it!=null) vm.importPlan(it) }
    var detail by rememberSaveable { mutableStateOf(false) }
    var exportReport by rememberSaveable { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf("") }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) runCatching {
            val payload=if(exportReport) requireNotNull(plan).toString(2) else context.assets.open("quant-example.json").bufferedReader().use { it.readText() }
            requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter().use { it.write(payload) }
        }.onFailure { notice="Could not save the JSON file." }.onSuccess { notice="JSON file saved." }
    }
    Panel(if (statement) "Portfolio action summary" else "Net trade plan") {
        Text(plan?.str("source")?.ifBlank { null } ?: "Import portfolio assumptions to estimate one-year outcomes.")
        if (statement && plan != null) {
            StatementSummary(plan)
            if (plan.has("total_value")) {
                if (plan.str("review").isNotBlank()) {
                    Text("AI interpretation",style=MaterialTheme.typography.titleSmall)
                    Text(plan.str("review"))
                }
                TextButton(onClick={vm.reviewPlan()},enabled=!vm.busy){Text(if(plan.str("review").isBlank()) "Review summary with NVIDIA" else "Refresh AI review")}
            }
            TextButton(onClick={advanced=!advanced}) { Text(if(advanced) "Hide planner tools" else "Open full optimizer") }
        }
        if(!statement || advanced) {
        OutlinedButton(onClick={picker.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!vm.busy){Text("Import planning JSON")}
        TextButton(onClick={exportReport=false;save.launch("PortfolioSense-example.json")},enabled=!vm.busy){Text("Save example input")}
        }
        if(notice.isNotEmpty()) Text(notice)
        if(plan!=null) {
            val ready=plan.str("status")=="ready"
            if(ready) {
                Metric("Expected gain vs holding · 1 year",money(plan.number("expected_net_gain_vs_hold")))
                Metric("Estimated tax and fees",money(plan.number("upfront_cost")))
                Metric("Annual fees saved",money(plan.number("annual_fee_change")))
                Metric("Cash after trades",money(plan.number("cash_after")))
                Metric("Turnover",percent(plan.getDouble("turnover")))
                Text("Scenario estimate in INR. Includes execution costs and assumes the supplied returns.")
                plan.rows("actions").forEach { a->
                    HorizontalDivider()
                    Text(a.str("action")+" · "+a.str("asset_id"),style=MaterialTheme.typography.titleSmall)
                    Metric("Allocation",percent(a.getDouble("current_weight"))+" → "+percent(a.getDouble("target_weight")))
                    Metric("Value / units",money(a.number("delta_value"))+" / "+a.getDouble("delta_units"))
                    a.optJSONObject("buy_leg")?.let { leg ->
                        Text("Buy "+leg.str("asset_id"))
                        Metric("Value / units",money(leg.number("delta_value"))+" / "+leg.getDouble("delta_units"))
                        Metric("Allocation",percent(leg.getDouble("current_weight"))+" → "+percent(leg.getDouble("target_weight")))
                    }
                    Metric("Est. tax / fees",money(a.number("est_tax_impact"))+" / "+money(a.number("est_fee_cost")))
                    Metric("Annual fee savings",money(a.number("est_annual_fee_savings")))
                    Text("Signal confidence "+a.getInt("confidence_score")+"/100 · "+a.str("rationale"))
                }
                TextButton(onClick={vm.reviewPlan()},enabled=!vm.busy){Text("Review plan with NVIDIA")}
                if(plan.str("review").isNotBlank()) Text(plan.str("review"))
            } else if (!statement) {
                Text(plan.str("reason").ifBlank { "More portfolio inputs are needed before orders can be calculated." })
                val missing=plan.strings("missing")
                if(missing.isNotEmpty()) Text("Inputs needed · "+missing.map { it.substringAfterLast('.').replace('_',' ') }.distinct().joinToString())
            }
            TextButton(onClick={detail=!detail}){Text(if(detail)"Hide exposure and costs" else "View exposure and costs")}
            if(detail) {
                val look=plan.optJSONObject("lookthrough") ?: JSONObject()
                val after=plan.optJSONObject("target_lookthrough")
                look.keys().forEach { k->Metric(if(statement) { val id=k.removePrefix("__unallocated:"); (if(k.startsWith("__unallocated:")) "Underlying mix unknown · " else "")+(plan.optJSONObject("names")?.optString(id,id) ?: label(k)) } else label(k),percent(look.getDouble(k))+(after?.let { " → "+percent(it.optDouble(k,0.0)) } ?: "")) }
                val breaches=plan.optJSONObject("concentration")
                if(!statement) breaches?.keys()?.forEach { k->Metric("Above cap · "+k,percent(breaches.getDouble(k))) }
                val duplicates=plan.optJSONObject("direct_fund_duplicates")
                duplicates?.keys()?.forEach { k->val row=duplicates.getJSONObject(k); Metric("Also held in funds · "+k,percent(row.getDouble("through_funds"))) }
                val overlap=plan.optJSONObject("overlap")
                overlap?.keys()?.forEach { a->val pair=overlap.getJSONObject(a);pair.keys().asSequence().filter { it>a }.forEach { b->Metric("Overlap · $a / $b",percent(pair.getDouble(b))) } }
                val fees=plan.optJSONObject("annual_expense_drag")
                fees?.keys()?.forEach { k->Metric("Annual expense · "+k,money(fees.number(k))) }
                plan.rows("switch_comparisons").forEach { s->Metric("Year 1 switch savings · "+s.str("asset_id")+" → "+s.str("target_asset_id"),money(s.number("first_year_net_savings"))) }
            }
            TextButton(onClick={exportReport=true;save.launch("PortfolioSense-plan.json")},enabled=!vm.busy){Text("Export plan JSON")}
        }
    }
}


@Composable
private fun StatementSummary(plan: JSONObject) {
    if (!plan.has("total_value")) {
        Text(plan.str("reason"))
        return
    }
    val scenarios = plan.rows("cap_scenarios")
    val cap = percent(plan.getDouble("stock_cap"))
    if (scenarios.isEmpty()) Text("Direct stock positions are within the $cap cap on the reported holdings.")
    else {
        Text("${scenarios.size} direct stock holdings above the $cap cap · ${money(plan.number("total_excess"))} total excess.",style=MaterialTheme.typography.titleMedium)
        Text("Cap-only scenario at statement prices. Sale proceeds stay in the portfolio as cash. Amounts are before tax and costs.")
        scenarios.forEach { row ->
            HorizontalDivider()
            Text(row.str("name"),style=MaterialTheme.typography.titleSmall)
            Metric("Current weight / cap",percent(row.getDouble("current_weight"))+" / "+cap)
            Metric("Value above cap",money(row.number("excess_value")))
            if (row.optBoolean("below_minimum")) Text("Below the planner’s ₹2,500 minimum trade. Monitor this breach.")
            else if (row.has("illustrative_units")) Text("Trimming ${row.getLong("illustrative_units")} shares at ${money(row.number("statement_price"))} would bring this holding to ${percent(row.getDouble("weight_after"))}.")
        }
        Text("Without selling, adding ${money(plan.number("new_cash_to_dilute"))} in cash would bring all direct stock positions within the cap at these prices.")
        Text("These figures measure concentration. Comparing after-tax outcomes requires tradeable lots, acquisition costs, charges and return assumptions.")
    }
    val funds = plan.rows("funds")
    if (funds.isNotEmpty()) {
        Text("Fund exposure",style=MaterialTheme.typography.titleSmall)
        Text(funds.joinToString { it.str("name")+" ("+percent(it.getDouble("weight"))+")" })
        Text("Underlying exposure and overlap need fund disclosures. Annual fee drag and switch savings need expense ratios for the current and alternative plans.")
    }
}
