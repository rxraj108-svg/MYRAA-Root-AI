package ai.myraa.assistant

import android.util.Base64
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Raw WebSocket client for the Gemini Live API (audio in / audio out / video frames / tool calls). */
class GeminiLive(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val system: String,
    private val tools: JSONArray,
    private val cb: Callback
) {
    interface Callback {
        fun onReady()
        fun onAudio(pcm: ByteArray)
        fun onInterrupted()
        fun onTurnComplete()
        fun onInText(t: String)
        fun onOutText(t: String)
        fun onToolCall(id: String, name: String, args: JSONObject)
        fun onClosed(reason: String)
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    @Volatile private var ws: WebSocket? = null
    @Volatile var ready = false
        private set
    @Volatile private var finished = false

    fun connect() {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setup().toString()) }
            override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { handle(bytes.utf8()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null); fin("closed $code $reason")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fin("closed $code $reason") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fin("error ${t.message ?: ""} ${response?.code ?: ""}")
            }
        })
    }

    private fun fin(reason: String) {
        ready = false
        if (!finished) { finished = true; cb.onClosed(reason) }
    }

    private fun setup(): JSONObject {
        val gen = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
        return JSONObject().put("setup", JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", gen)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject())))
    }

    private fun handle(text: String) {
        val j = try { JSONObject(text) } catch (_: Exception) { return }
        if (j.has("setupComplete")) { ready = true; cb.onReady(); return }
        j.optJSONObject("serverContent")?.let { sc ->
            if (sc.optBoolean("interrupted")) cb.onInterrupted()
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val d = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data").orEmpty()
                    if (d.isNotEmpty()) cb.onAudio(Base64.decode(d, Base64.DEFAULT))
                }
            }
            sc.optJSONObject("inputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) cb.onInText(it) }
            sc.optJSONObject("outputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) cb.onOutText(it) }
            if (sc.optBoolean("turnComplete")) cb.onTurnComplete()
        }
        j.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { fc ->
            for (i in 0 until fc.length()) {
                val f = fc.getJSONObject(i)
                cb.onToolCall(f.optString("id"), f.optString("name"), f.optJSONObject("args") ?: JSONObject())
            }
        }
        if (j.has("goAway")) fin("server asked to reconnect")
    }

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    fun sendAudio(pcm: ByteArray) {
        if (!ready) return
        send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
            .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
            .put("mimeType", "audio/pcm;rate=16000"))))
    }

    fun sendFrame(jpeg: ByteArray) {
        if (!ready) return
        send(JSONObject().put("realtimeInput", JSONObject().put("video", JSONObject()
            .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            .put("mimeType", "image/jpeg"))))
    }

    fun sendText(t: String) {
        if (!ready) return
        send(JSONObject().put("realtimeInput", JSONObject().put("text", t)))
    }

    fun sendToolResponse(id: String, name: String, response: JSONObject) {
        send(JSONObject().put("toolResponse", JSONObject().put("functionResponses",
            JSONArray().put(JSONObject().put("id", id).put("name", name).put("response", response)))))
    }

    fun close() { ready = false; finished = true; try { ws?.close(1000, "bye") } catch (_: Exception) {} }
}
