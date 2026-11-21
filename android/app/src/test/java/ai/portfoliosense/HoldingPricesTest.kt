package ai.portfoliosense
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HoldingPricesTest {
    @Test fun exactEtfListing() {
        val csv="Symbol,Underlying Asset,SecurityName,DateofListing,MarketLot,ISINNumber\nTATAGOLD,Tata Gold ETF,TATAAML-TATAGOLD,17-Jan-24,1,INF277KA1976"
        val data=listings(csv,true).single()
        assertEquals("TATAGOLD",data.symbol)
        assertEquals("INF277KA1976",data.isin)
    }
    @Test fun marketRevaluationPreservesStatementAndBasis() {
        val row=JSONObject("""{"isin":"INE848E01016","quantity":10,"current_value":700,"invested_value":680}""")
        val price=MarketPrice(76.61,1795168690,"NSE",false)
        val market=applyPrice(row,price.json())
        assertEquals(766.10,market.getDouble("current_value"),0.001)
        assertEquals(86.10,market.pnl()!!,0.001)
        assertEquals(700.0,row.getDouble("current_value"),0.0)
        assertSame(row,applyPrice(row,null))
    }
    @Test fun navUsesIsinAndProviderDate() {
        val text="Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;Net Asset Value;Date\n135762;INF846K01WO1;-;Axis Children's Fund;Direct Plan;Growth Option;31.3446;19-Nov-2026"
        val h=JSONObject("""{"isin":"INF846K01WO1","instrument_type":"mutual_fund"}""")
        val q=navPrices(text,listOf(h)).getValue("INF846K01WO1")
        assertEquals(31.3446,q.price,0.0)
        assertTrue(q.daily)
    }
    @Test fun rejectsWrongExchangeSymbolOrCurrency() {
        val data="""{"chart":{"result":[{"meta":{"currency":"INR","symbol":"NHPC.NS","regularMarketPrice":76.61,"regularMarketTime":1795168690}}]}}"""
        assertEquals(76.61,chartPrice(data,"NHPC.NS")!!.price,0.0)
        assertNull(chartPrice(data,"INFY.NS"))
        assertNull(chartPrice(data.replace("INR","USD"),"NHPC.NS"))
    }
}
