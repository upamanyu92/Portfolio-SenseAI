package ai.portfoliosense

import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject

/** Snapshot scenarios use statement values, not executable quotes or tax forecasts. */
internal fun statementInsight(report: JSONObject, cap: Double = 0.10): JSONObject {
    require(cap > 0 && cap <= 1)
    val plan = JSONObject().put("source", "Latest statement").put("statement_version", 1)
        .put("status", "inputs_required").put("stock_cap", cap)
    val holdings = report.rows("holdings")
    val groups = holdings.groupBy { it.str("isin").ifBlank { it.str("name") } }
    if (holdings.isEmpty() || groups.containsKey("") ||
        holdings.any { it.number("current_value")?.let { v -> v >= 0 } != true }) {
        return plan.put("reason", "A closing value in INR and a security name or ISIN are needed for every position to measure concentration.")
    }
    val total = holdings.sumOf { it.getDouble("current_value") }
    if (total <= 0) return plan.put("reason", "The statement has no positive closing value.")
    val look = JSONObject()
    val names = JSONObject()
    val flags = JSONObject()
    val rows = JSONArray()
    val funds = JSONArray()
    var totalExcess = 0.0
    var newCash = 0.0
    for ((id, lines) in groups.entries.sortedByDescending { e -> e.value.sumOf { it.getDouble("current_value") } }) {
        val name = lines.first().str("name").ifBlank { id }
        names.put(id, name)
        val value = lines.sumOf { it.getDouble("current_value") }
        val weight = value / total
        val kind = lines.map { it.str("instrument_type") }.distinct().singleOrNull()
        if (kind == "mutual_fund") {
            funds.put(JSONObject().put("name", name).put("weight", weight))
            look.put("__unallocated:"+id, weight)
        } else look.put(id, weight)
        if (kind != "stock" || weight <= cap + 1e-10) continue
        val excess = value - total * cap
        val row = JSONObject().put("name", name).put("asset_id", id).put("current_weight", weight)
            .put("cap", cap).put("excess_value", excess)
            .put("below_minimum", excess < 2500.0)
        if (lines.all { (it.number("quantity") ?: 0.0) > 0 }) {
            val qty = lines.sumOf { it.getDouble("quantity") }
            val price = value / qty
            val units = ceil(excess / price - 1e-9)
            if (qty % 1.0 == 0.0 && units in 1.0..qty) {
                row.put("illustrative_units", units.toLong()).put("statement_price", price)
                    .put("gross_sale_value", units * price)
                    .put("weight_after", (value - units * price) / total)
            }
        }
        row.put("below_minimum", (row.number("gross_sale_value") ?: excess) < 2500.0)
        rows.put(row)
        flags.put(id, weight)
        totalExcess += excess
        newCash = maxOf(newCash, value / cap - total)
    }
    plan.put("total_value", total).put("lookthrough", look).put("names", names)
        .put("concentration", flags).put("cap_scenarios", rows).put("funds", funds)
        .put("total_excess", totalExcess).put("new_cash_to_dilute", newCash)
    if (holdings.all { it.number("invested_value") != null }) {
        plan.put("line_item_pnl", total - holdings.sumOf { it.getDouble("invested_value") })
    }
    return plan
}
