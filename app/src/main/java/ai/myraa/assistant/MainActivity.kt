package ai.myraa.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val tick = mutableIntStateOf(0)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick.intValue++ }
    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            val i = Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_PROJ)
                .putExtra("code", r.resultCode).putExtra("data", d)
            ContextCompat.startForegroundService(this, i)
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF8B7BFF), background = Color(0xFF06060C), surface = Color(0xFF14141F)
            )) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Main() } }
        }
    }

    override fun onResume() { super.onResume(); tick.intValue++ }

    private fun micOk() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun accOk(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/${MyraaAccessibilityService::class.java.name}")
    }
    private fun overlayOk() = Settings.canDrawOverlays(this)
    private fun batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun requestRuntime() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(p.toTypedArray())
    }
    private fun appInfo() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Main() {
        val ctx: Context = this@MainActivity
        val t = tick.intValue
        val running by AppState.running.collectAsState()
        val status by AppState.status.collectAsState()
        val emotion by AppState.emotion.collectAsState()
        val level by AppState.level.collectAsState()
        val muted by AppState.muted.collectAsState()
        val log by AppState.log.collectAsState()

        var key by remember { mutableStateOf(Prefs.get(ctx, Prefs.KEY, "")) }
        var model by remember { mutableStateOf(Prefs.get(ctx, Prefs.MODEL, Prefs.DEFAULT_MODEL)) }
        var lang by remember { mutableStateOf(Prefs.get(ctx, Prefs.LANG, "Bengali, Hindi and English - mirror whatever the user speaks")) }
        var name by remember { mutableStateOf(Prefs.get(ctx, Prefs.NAME, "")) }
        var voice by remember { mutableStateOf(Prefs.get(ctx, Prefs.VOICE, "Aoede")) }
        var watch by remember { mutableStateOf(Prefs.getBool(ctx, Prefs.WATCH, false)) }
        var showKey by remember { mutableStateOf(false) }

        val mic = remember(t) { micOk() }
        val acc = remember(t) { accOk() }
        val ovl = remember(t) { overlayOk() }
        val bat = remember(t) { batteryOk() }

        val col = emoColor(emotion)
        val scale by animateFloatAsState(1f + level * 0.4f, label = "orb")

        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("MYRAA", fontSize = 26.sp, color = Color.White)
            Canvas(Modifier.size(210.dp)) {
                val r = size.minDimension / 2
                drawCircle(color = col.copy(alpha = 0.22f), radius = r * scale)
                drawCircle(
                    brush = Brush.radialGradient(listOf(col, col.copy(alpha = 0.35f)), center = Offset(size.width / 2, size.height / 2), radius = r * 0.7f),
                    radius = r * 0.62f * (0.95f + level * 0.2f)
                )
            }
            Text(status, color = Color(0xFFB8B8D0))

            Button(
                modifier = Modifier.fillMaxWidth().height(54.dp),
                onClick = {
                    when {
                        running -> startService(Intent(ctx, AssistantService::class.java).setAction(AssistantService.ACTION_STOP))
                        !micOk() -> requestRuntime()
                        key.isBlank() -> Toast.makeText(ctx, "Paste your API key first", Toast.LENGTH_LONG).show()
                        else -> ContextCompat.startForegroundService(ctx, Intent(ctx, AssistantService::class.java).setAction(AssistantService.ACTION_START))
                    }
                }
            ) { Text(if (running) "STOP" else "START MYRAA") }

            if (running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { AppState.muted.value = !muted }) { Text(if (muted) "Unmute mic" else "Mute mic") }
                    OutlinedButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 30 && acc) Toast.makeText(ctx, "Not needed on Android 11+", Toast.LENGTH_SHORT).show()
                        else projLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                    }) { Text("Allow screen capture") }
                }
            }

            var rootEnabled by remember { mutableStateOf(Prefs.getBool(ctx, "root_enabled", false)) }
            Section("Root access") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (rootEnabled) "✅ Root enabled" else "⚠️ Root not enabled", Modifier.weight(1f), fontSize = 14.sp)
                    Button(onClick = {
                        Thread {
                            val granted = try {
                                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                                val output = p.inputStream.bufferedReader().readText()
                                p.waitFor() == 0 && output.contains("uid=0")
                            } catch (_: Exception) { false }
                            runOnUiThread {
                                rootEnabled = granted
                                Prefs.putBool(ctx, "root_enabled", granted)
                                Toast.makeText(ctx, if (granted) "Root access granted" else "Root unavailable or denied", Toast.LENGTH_LONG).show()
                                tick.intValue++
                            }
                        }.start()
                    }) { Text(if (rootEnabled) "Check root" else "Enable root") }
                }
                Text("Tap to request Superuser permission. Grant the prompt in your root manager. Root actions are available only while enabled.", fontSize = 12.sp, color = Color(0xFFB8B8D0))
            }

            Section("Setup (one time)") {
                PermRow("Microphone, notifications, calls", mic) { requestRuntime() }
                PermRow("Phone control (Accessibility)", acc) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                if (!acc) Text(
                    "Android 13+: if the switch is greyed out, tap 'App info', open the ⋮ menu and choose 'Allow restricted settings', then enable MYRAA in Accessibility.",
                    fontSize = 12.sp, color = Color(0xFFFFC46B)
                )
                if (!acc) OutlinedButton(onClick = { appInfo() }) { Text("App info") }
                PermRow("Display over other apps", ovl) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                PermRow("Run in background (no battery limit)", bat) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }
            }

            Section("AI key & voice") {
                OutlinedTextField(
                    value = key, onValueChange = { key = it; Prefs.put(ctx, Prefs.KEY, it.trim()) },
                    label = { Text("Gemini API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                    OutlinedButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }) { Text("Get free key") }
                }
                OutlinedTextField(value = name, onValueChange = { name = it; Prefs.put(ctx, Prefs.NAME, it) },
                    label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = lang, onValueChange = { lang = it; Prefs.put(ctx, Prefs.LANG, it) },
                    label = { Text("Languages") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = model, onValueChange = { model = it; Prefs.put(ctx, Prefs.MODEL, it.trim()) },
                    label = { Text("Live model name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Voice", color = Color(0xFFB8B8D0), fontSize = 12.sp)
                Row(Modifier.horizontalScroll2(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Aoede", "Kore", "Leda", "Zephyr", "Despina", "Callirrhoe").forEach { v ->
                        Button(
                            onClick = { voice = v; Prefs.put(ctx, Prefs.VOICE, v) },
                            colors = ButtonDefaults.buttonColors(containerColor = if (voice == v) MaterialTheme.colorScheme.primary else Color(0xFF2A2A3A))
                        ) { Text(v, fontSize = 12.sp) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = watch, onCheckedChange = { watch = it; Prefs.putBool(ctx, Prefs.WATCH, it) })
                    Spacer(Modifier.width(8.dp))
                    Text("Always watch my screen (uses more data)", fontSize = 13.sp)
                }
            }

            Section("Conversation") {
                if (log.isEmpty()) Text("Nothing yet", color = Color(0xFF777790))
                log.takeLast(12).forEach { Text(it, fontSize = 13.sp) }
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF14141F))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, color = Color(0xFF8B7BFF), fontSize = 13.sp)
                content()
            }
        }
    }

    @Composable
    private fun PermRow(label: String, ok: Boolean, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (ok) "✅" else "⚠️")
            Spacer(Modifier.width(8.dp))
            Text(label, Modifier.weight(1f), fontSize = 14.sp)
            if (!ok) Button(onClick = onClick) { Text("Allow") }
        }
    }

    private fun emoColor(e: String) = when (e.lowercase()) {
        "happy" -> Color(0xFFFFB300)
        "excited" -> Color(0xFFFF4D8D)
        "sad" -> Color(0xFF4A90E2)
        "love" -> Color(0xFFFF6FB5)
        "surprised" -> Color(0xFF2EE6C5)
        "angry" -> Color(0xFFFF5252)
        else -> Color(0xFF7C5CFF)
    }
}

@Composable
private fun Modifier.horizontalScroll2(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))
