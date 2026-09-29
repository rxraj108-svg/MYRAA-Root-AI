package ai.myraa.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.sqrt

/** Mic capture (16 kHz PCM) + speaker playback (24 kHz PCM), tuned for low latency and echo cancellation. */
class AudioIO(private val ctx: Context, private val onMic: (ByteArray) -> Unit) {
    @Volatile private var run = false
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var am: AudioManager? = null
    private val q = LinkedBlockingQueue<ByteArray>()

    @SuppressLint("MissingPermission")
    fun start() {
        am = ctx.getSystemService(AudioManager::class.java)
        am?.mode = AudioManager.MODE_IN_COMMUNICATION
        route()
        val minIn = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, 3200) * 2
        )
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.setEnabled(true)
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.setEnabled(true)
        rec = r

        val minOut = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(24000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(maxOf(minOut, 9600))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = t
        run = true
        r.startRecording()
        t.play()

        thread(name = "myraa-mic", isDaemon = true) {
            val buf = ByteArray(640) // 20 ms chunks = lowest latency
            while (run) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    onMic(buf.copyOf(n))
                    AppState.level.value = rms(buf, n)
                } else if (n < 0) break
            }
        }
        thread(name = "myraa-speaker", isDaemon = true) {
            while (run) {
                val b = q.poll(200, TimeUnit.MILLISECONDS) ?: continue
                AppState.level.value = rms(b, b.size)
                t.write(b, 0, b.size)
            }
        }
    }

    fun play(pcm: ByteArray) { q.offer(pcm) }

    /** Called when the user interrupts (barge-in): drop queued speech instantly. */
    fun flush() {
        q.clear()
        try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
    }

    fun stop() {
        run = false
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        rec = null; track = null
        val a = am ?: return
        a.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice() else a.isSpeakerphoneOn = false
    }

    private fun route() {
        val a = am ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            val devs = a.availableCommunicationDevices
            val headset = intArrayOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET
            )
            val pick = devs.firstOrNull { it.type in headset }
                ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (pick != null) a.setCommunicationDevice(pick)
        } else {
            @Suppress("DEPRECATION")
            a.isSpeakerphoneOn = true
        }
    }

    private fun rms(b: ByteArray, n: Int): Float {
        var s = 0.0
        var i = 0
        val cnt = n / 2
        if (cnt == 0) return 0f
        while (i + 1 < n) {
            val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt() / 32768.0
            s += v * v
            i += 2
        }
        return min(1f, (sqrt(s / cnt) * 4).toFloat())
    }
}
