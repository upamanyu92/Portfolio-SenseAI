package ai.portfoliosense

import android.content.Context
import android.webkit.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/** Execute the pinned Python engine on a local HTTPS origin. Portfolio JSON is data only. */
internal object QuantRuntime {
    private const val origin = "https://quant.portfoliosense.invalid/"
    suspend fun run(context: Context, input: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        val web = WebView(context)
        val loaded = CompletableDeferred<Unit>()
        try {
            web.settings.javaScriptEnabled = true
            web.settings.allowFileAccess = false
            web.settings.allowContentAccess = false
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val path=request.url.lastPathSegment.orEmpty()
                    if(request.url.toString()==origin+path && path.matches(Regex("[A-Za-z0-9_.-]+"))) {
                        val mime=when(path.substringAfterLast('.')) {
                            "js"->"text/javascript"; "wasm"->"application/wasm"; "html"->"text/html"; "json"->"application/json"; else->"application/octet-stream"
                        }
                        try {return WebResourceResponse(mime,null,context.assets.open("quant/"+path))} catch(_:java.io.IOException) {}
                    }
                    return WebResourceResponse("text/plain","UTF-8",404,"Missing",emptyMap(),ByteArrayInputStream(byteArrayOf()))
                }
                override fun onPageFinished(view:WebView,url:String) { if(url==origin+"index.html") loaded.complete(Unit) }
            }
            withTimeout(180000) {
                web.loadUrl(origin+"index.html")
                loaded.await()
                web.evaluateJavascript("void calculate(JSON.parse("+JSONObject.quote(input.toString())+"))",null)
                var output:JSONObject?=null
                while(output==null) {
                    delay(150)
                    val response=suspendCancellableCoroutine<String> { cont ->
                        web.evaluateJavascript("typeof result === 'string' ? result : null") { if(cont.isActive) cont.resume(it) }
                    }
                    if(response!="null") output=JSONObject(JSONTokener(response).nextValue() as String)
                }
                output
            }
        } finally { web.destroy() }
    }
}
