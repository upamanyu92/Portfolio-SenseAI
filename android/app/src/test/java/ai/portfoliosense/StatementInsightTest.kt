package ai.portfoliosense

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StatementInsightTest {
    private fun holding(name:String, id:String, value:Double, qty:Double?, type:String="stock") =
        JSONObject().put("name",name).put("isin",id).put("current_value",value)
            .put("quantity",qty).put("instrument_type",type)

    @Test fun reportsNamedCapScenariosAndUnknownOverlap() {
        val report=JSONObject().put("holdings",JSONArray()
            .put(holding("Stock A","A",22990.0,200.0))
            .put(holding("Stock B","B",13780.0,null))
            .put(holding("Stock C","C",10340.0,100.0))
            .put(holding("Fund","F",52890.0,300.0,"mutual_fund")))
        val summary=statementInsight(report)
        assertEquals(17110.0,summary.getDouble("total_excess"),0.001)
        assertEquals(129900.0,summary.getDouble("new_cash_to_dilute"),0.001)
        val rows=summary.rows("cap_scenarios")
        assertEquals("Stock A",rows[0].str("name"))
        assertEquals(114,rows[0].getInt("illustrative_units"))
        assertFalse(rows[1].has("illustrative_units"))
        assertTrue(rows[2].getBoolean("below_minimum"))
        assertFalse(summary.has("overlap"))
        assertEquals("Fund",summary.rows("funds").single().str("name"))
    }

    @Test fun unknownValueDoesNotBecomeZero() {
        val report=JSONObject().put("holdings",JSONArray().put(holding("A","A",100.0,1.0))
            .put(JSONObject().put("name","B").put("instrument_type","stock")))
        assertFalse(statementInsight(report).has("total_value"))
    }
}
