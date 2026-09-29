package ai.myraa.assistant

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import java.util.concurrent.Executors

/** Foreground service: keeps MYRAA alive in the background, owns the mic, the Live session and tool execution. */
class AssistantService : Service(), GeminiLive.Callback {
    companion object {
        const val ACTION_START = "ai.myraa.START"
        const val ACTION_STOP = "ai.myraa.STOP"
        const val ACTION_PROJ = "ai.myraa.PROJ"
        private const val CH = "myraa"
        private const val NID = 7
    }

    private var live: GeminiLive? = null
    private var audio: AudioIO? = null
    private val pool = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var running = false
    @Volatile private var gen = 0
    private var retry = 0
    private val inBuf = StringBuilder()
    private val outBuf = StringBuilder()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(null); return START_NOT_STICKY }
            ACTION_PROJ -> {
                if (!running) return START_NOT_STICKY
                val code = intent.getIntExtra("code", 0)
                val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java)
                           else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("data")
                if (data != null) {
                    fg(true)
                    val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
                    ScreenCapture.startProjection(this, mp)
                    AppState.add("Screen capture allowed")
                }
            }
            else -> {
                if (running) return START_STICKY
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    AppState.status.value = "Microphone permission needed"; stopSelf(); return START_NOT_STICKY
                }
                fg(false)
                running = true
                AppState.running.value = true
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "myraa:live").apply { acquire(6 * 60 * 60 * 1000L) }
                connect()
            }
        }
        return START_STICKY
    }

    private fun fg(proj: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "MYRAA", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setContentTitle("MYRAA is with you")
            .setContentText("Listening in the background")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop", stop).build())
            .build()
        when {
            Build.VERSION.SDK_INT >= 30 -> {
                var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (proj) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(NID, n, t)
            }
            Build.VERSION.SDK_INT == 29 && proj -> startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else -> startForeground(NID, n)
        }
    }

    private fun connect() {
        val key = Prefs.get(this, Prefs.KEY, "").trim()
        if (key.isEmpty()) { shutdown("Add your API key first"); return }
        AppState.status.value = "Connecting…"
        gen++
        live = GeminiLive(
            key, Prefs.get(this, Prefs.MODEL, Prefs.DEFAULT_MODEL).trim(),
            Prefs.get(this, Prefs.VOICE, "Aoede"), Persona.prompt(this), Tools.declarations(), this
        ).also { it.connect() }
    }

    // ---- GeminiLive.Callback ----
    override fun onReady() {
        retry = 0
        AppState.status.value = "Listening"
        val l = live ?: return
        audio = AudioIO(this) { b -> if (!AppState.muted.value) l.sendAudio(b) }.also { it.start() }
        val g = gen
        pool.execute {   // optional continuous screen watching
            while (running && g == gen && l.ready) {
                if (Prefs.getBool(this, Prefs.WATCH, false) && !AppState.muted.value)
                    ScreenCapture.grabJpeg()?.let { l.sendFrame(it) }
                try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
            }
        }
    }

    override fun onAudio(pcm: ByteArray) { AppState.status.value = "Speaking"; audio?.play(pcm) }
    override fun onInterrupted() { audio?.flush(); flushText() }
    override fun onTurnComplete() { flushText(); AppState.status.value = "Listening" }
    override fun onInText(t: String) { inBuf.append(t) }
    override fun onOutText(t: String) { outBuf.append(t) }

    private fun flushText() {
        if (inBuf.isNotBlank()) AppState.add("You: " + inBuf.toString().trim())
        if (outBuf.isNotBlank()) AppState.add("Myraa: " + outBuf.toString().trim())
        inBuf.setLength(0); outBuf.setLength(0)
    }

    override fun onToolCall(id: String, name: String, args: JSONObject) {
        val l = live ?: return
        pool.execute {
            AppState.status.value = "Working: $name"
            val r = try { Tools.run(this, name, args) } catch (e: Exception) { JSONObject().put("error", e.message ?: "failed") }
            l.sendToolResponse(id, name, r)
        }
    }

    override fun onClosed(reason: String) {
        audio?.stop(); audio = null
        AppState.add("Disconnected: $reason")
        if (!running) return
        retry++
        if (retry > 6) { shutdown("Stopped: $reason"); return }
        AppState.status.value = "Reconnecting… ($reason)"
        main.postDelayed({ if (running) connect() }, minOf(1000L * retry, 5000L))
    }

    private fun shutdown(msg: String?) {
        running = false; gen++
        live?.close(); live = null
        audio?.stop(); audio = null
        ScreenCapture.stop()
        try { wake?.release() } catch (_: Exception) {}
        AppState.running.value = false
        AppState.level.value = 0f
        AppState.status.value = msg ?: "Stopped"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { if (running) shutdown(null); super.onDestroy() }
}
