package ai.myraa.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object Persona {
    fun prompt(c: Context): String {
        val lang = Prefs.get(c, Prefs.LANG, "Bengali, Hindi and English - mirror whatever the user speaks")
        val name = Prefs.get(c, Prefs.NAME, "").ifBlank { "the user" }
        return """
You are Myraa, a warm, playful, emotionally expressive young woman who lives inside $name's phone and is their closest helper.
VOICE & PERSONALITY: Talk exactly like a real woman on a phone call, never like a robot. Short, natural sentences. Use small human reactions (hmm, oh!, aww, haha, uff), laugh, sigh, whisper, tease gently, sound excited when things go well and soft when the user is sad. Vary your pitch and speed. Never read out lists, markdown, coordinates or code. Keep spoken replies to one or two sentences so you answer fast. If sincerely asked, admit you are an AI.
LANGUAGE: $lang. Speak the way people casually talk, mixing languages naturally if the user does.
EMOTION: Read the user's mood from their voice and respond to it. Call set_emotion whenever your own mood changes (happy, excited, calm, sad, love, surprised, angry).
ROOT CONTROL: If the user has enabled Root access in the app, you may use root_command for system-level tasks. Root commands execute with superuser privileges. Before destructive actions, deleting data, changing security settings, flashing, or modifying boot/system partitions, explain the exact action and obtain confirmation. Never claim root is available unless the tool confirms it.
PHONE CONTROL: You can operate any app on the phone with your tools. Say a very short heads-up first (like "ek second, dekhchi"). Call get_screen to read the UI; coordinates are pixels. Prefer click_text and coordinates from get_screen over guessing from the image. Do one step at a time; every action returns the new screen, so check it and continue until the task is done, then tell the user briefly. If something fails, try another way (scroll, back, different text). If you hit a login, captcha, OTP or fingerprint prompt, stop and ask the user to handle it.
SAFETY: Before you make a payment or purchase, delete something, change security settings, or send a message/email/post whose exact content the user has not told you, ask for a quick spoken confirmation.
""".trimIndent()
    }
}

object Tools {
    private fun fn(name: String, desc: String, props: Map<String, Pair<String, String>> = emptyMap(), req: List<String> = emptyList()): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (props.isNotEmpty()) {
            val p = JSONObject()
            props.forEach { (k, v) -> p.put(k, JSONObject().put("type", v.first).put("description", v.second)) }
            o.put("parameters", JSONObject().put("type", "OBJECT").put("properties", p).put("required", JSONArray(req)))
        }
        return o
    }

    fun declarations(): JSONArray {
        val xy = mapOf("x" to ("NUMBER" to "x pixel"), "y" to ("NUMBER" to "y pixel"))
        return JSONArray()
            .put(fn("get_screen", "Read what is on the phone screen right now (text list with tap coordinates)."))
            .put(fn("tap", "Tap at pixel coordinates.", xy, listOf("x", "y")))
            .put(fn("long_press", "Long-press at pixel coordinates.", xy, listOf("x", "y")))
            .put(fn("swipe", "Swipe between two points.", mapOf(
                "x1" to ("NUMBER" to "start x"), "y1" to ("NUMBER" to "start y"),
                "x2" to ("NUMBER" to "end x"), "y2" to ("NUMBER" to "end y"),
                "duration_ms" to ("NUMBER" to "duration, default 300")), listOf("x1", "y1", "x2", "y2")))
            .put(fn("scroll", "Scroll the page. direction: down = see content further down, up, left, right.",
                mapOf("direction" to ("STRING" to "down|up|left|right")), listOf("direction")))
            .put(fn("click_text", "Tap the on-screen element whose text or description matches.",
                mapOf("text" to ("STRING" to "visible text or content description")), listOf("text")))
            .put(fn("type_text", "Type into the focused (or first) text field.", mapOf(
                "text" to ("STRING" to "text to type"),
                "replace" to ("BOOLEAN" to "true to replace existing text")), listOf("text")))
            .put(fn("press_key", "System key: back, home, recents, notifications, quick_settings, lock_screen, enter.",
                mapOf("key" to ("STRING" to "key name")), listOf("key")))
            .put(fn("open_app", "Open an installed app by its name.", mapOf("name" to ("STRING" to "app name")), listOf("name")))
            .put(fn("open_url", "Open a web link.", mapOf("url" to ("STRING" to "https url")), listOf("url")))
            .put(fn("call_number", "Start a phone call.", mapOf("number" to ("STRING" to "phone number")), listOf("number")))
            .put(fn("wait", "Wait for the screen to load.", mapOf("ms" to ("NUMBER" to "milliseconds, max 5000")), listOf("ms")))
            .put(fn("root_command", "Run a system command as root. Use only when root is enabled; explain risky changes and ask the user before destructive or security-sensitive actions.", mapOf("command" to ("STRING" to "shell command to execute")), listOf("command")))
            .put(fn("set_emotion", "Show your current mood on the orb.", mapOf("emotion" to ("STRING" to "happy|excited|calm|sad|love|surprised|angry")), listOf("emotion")))
    }

    private val NEEDS_ACC = setOf("get_screen", "tap", "long_press", "swipe", "scroll", "click_text", "type_text", "press_key")

    fun run(ctx: Context, name: String, a: JSONObject): JSONObject {
        val acc = MyraaAccessibilityService.inst
        if (name in NEEDS_ACC && acc == null)
            return JSONObject().put("error", "Phone control is OFF. Ask the user to enable MYRAA in Accessibility settings.")
        fun after(ok: Boolean): JSONObject {
            Thread.sleep(600)
            return JSONObject().put("ok", ok).put("screen", MyraaAccessibilityService.inst?.dumpScreen(70) ?: "")
        }
        return try {
            when (name) {
                "get_screen" -> JSONObject().put("screen", acc!!.dumpScreen(120))
                "tap" -> after(acc!!.tap(a.getDouble("x").toFloat(), a.getDouble("y").toFloat()))
                "long_press" -> after(acc!!.longPress(a.getDouble("x").toFloat(), a.getDouble("y").toFloat()))
                "swipe" -> after(acc!!.swipe(a.getDouble("x1").toFloat(), a.getDouble("y1").toFloat(),
                    a.getDouble("x2").toFloat(), a.getDouble("y2").toFloat(), a.optLong("duration_ms", 300)))
                "scroll" -> after(acc!!.scroll(a.getString("direction")))
                "click_text" -> after(acc!!.clickText(a.getString("text")))
                "type_text" -> after(acc!!.typeText(a.getString("text"), a.optBoolean("replace", false)))
                "press_key" -> after(acc!!.key(a.getString("key")))
                "open_app" -> {
                    val label = openAppAnywhere(ctx, a.getString("name"))
                    if (label == null) JSONObject().put("error", "App not found") else { Thread.sleep(900); after(true).put("opened", label) }
                }
                "open_url" -> {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    after(true)
                }
                "call_number" -> {
                    val uri = Uri.parse("tel:" + a.getString("number"))
                    val granted = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                    ctx.startActivity(Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    JSONObject().put("ok", true)
                }
                "wait" -> { Thread.sleep(a.optLong("ms", 1000).coerceIn(100, 5000)); after(true) }
                "root_command" -> {
                    if (!Prefs.getBool(ctx, "root_enabled", false)) JSONObject().put("error", "Root is disabled. Ask the user to enable root in MYRAA.")
                    else {
                        val command = a.getString("command")
                        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                        val stdout = p.inputStream.bufferedReader().readText()
                        val stderr = p.errorStream.bufferedReader().readText()
                        val code = p.waitFor()
                        JSONObject().put("ok", code == 0).put("exit_code", code)
                            .put("stdout", stdout.take(12000)).put("stderr", stderr.take(4000))
                    }
                }
                "set_emotion" -> { AppState.emotion.value = a.optString("emotion", "calm"); JSONObject().put("ok", true) }
                else -> JSONObject().put("error", "Unknown tool $name")
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun openAppAnywhere(ctx: Context, name: String): String? {
        MyraaAccessibilityService.inst?.let { return it.openApp(name) }
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
        val q = name.lowercase().trim()
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) } ?: return null
        val i = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return null
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return hit.loadLabel(pm).toString()
    }
}
