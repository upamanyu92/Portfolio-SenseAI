package ai.portfoliosense

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class NvidiaAnalyzerTest {
    private val report =
        """{"sentiment_score":48,"confidence_score":70,"logic_breakdown":"One position","suggested_move":"Review concentration","tickers":[],"risk_flags":[],"data_verifier":[],"top_actions":[],"holdings":[{"name":"Example","quantity":2,"invested_value":null}]}"""

    private fun call(body: String, status: Int = 200): JSONObject {
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    assertEquals(
                        "https://integrate.api.nvidia.com/v1/chat/completions",
                        request.url.toString(),
                    )
                    assertEquals("Bearer fixture", request.header("Authorization"))
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    val payload = JSONObject(buffer.readUtf8())
                    assertEquals(NvidiaAnalyzer.model, payload.getString("model"))
                    assertTrue(payload.getBoolean("stream"))
                    assertTrue(
                        payload.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking")
                    )
                    assertEquals(16384, payload.getInt("reasoning_budget"))
                    Response.Builder()
                        .request(request)
                        .code(status)
                        .message("test")
                        .protocol(Protocol.HTTP_1_1)
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        return NvidiaAnalyzer.analyze(client, "fixture", "EXAMPLE holdings")
    }

    private fun chunk(key: String, value: String): String {
        val choice = JSONObject().put("delta", JSONObject().put(key, value))
        return "data: " + JSONObject().put("choices", JSONArray().put(choice)) + "\n\n"
    }

    @Test
    fun assemblesReportFromContentOnly() {
        val returned =
            call(
                "data: {\"choices\":[]}\n\n" +
                    chunk("reasoning_content", "private") +
                    chunk("content", report.take(30)) +
                    chunk("content", report.drop(30)) +
                    "data: [DONE]\n\n"
            )
        assertEquals("NVIDIA Nemotron", returned.getString("provider"))
        assertEquals(48, returned.getInt("sentiment_score"))
        assertFalse(returned.toString().contains("private"))
    }

    @Test
    fun rejectsInterruptedStream() {
        assertThrows(IllegalArgumentException::class.java) { call(chunk("content", report)) }
    }

    @Test
    fun rejectsInvalidScores() {
        assertThrows(java.io.IOException::class.java) {
            NvidiaAnalyzer.parseReport(report.replace("48", "148"))
        }
    }

    @Test
    fun handlesInvalidKey() {
        assertTrue(
            assertThrows(IllegalStateException::class.java) { call("", 401) }
                .message!!
                .contains("key")
        )
    }
}
