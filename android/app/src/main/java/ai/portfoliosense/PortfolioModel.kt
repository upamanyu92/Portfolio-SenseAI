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

    private val holdingsSource
        get() = result ?: sample

    val holdings
        get() = holdingsSource.rows("holdings")

    val score
        get() = result?.optInt("sentiment_score") ?: sample.getInt("healthScore")

    init {
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
        prefs.edit().putString("endpoint", endpoint).apply()
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
        if (busy || uris.isEmpty() || endpoint.isEmpty()) return
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
                            prefs.edit().putString("result", report.toString()).apply()
                            result = report
                            update("Complete")
                            message = "Analysis complete"
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
