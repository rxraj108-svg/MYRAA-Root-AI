package ai.myraa.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The "hands" of MYRAA: reads the screen tree and performs taps, swipes, typing and system actions. */
class MyraaAccessibilityService : AccessibilityService() {
    companion object { @Volatile var inst: MyraaAccessibilityService? = null }

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- gestures (call from a background thread) ----
    private fun gesture(p: Path, dur: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur.coerceAtLeast(1))).build()
        val posted = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        if (!posted) return false
        latch.await(4, TimeUnit.SECONDS)
        return ok
    }

    fun tap(x: Float, y: Float) = gesture(Path().apply { moveTo(x, y) }, 50)
    fun longPress(x: Float, y: Float) = gesture(Path().apply { moveTo(x, y) }, 700)
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)

    fun scroll(direction: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        return when (direction.lowercase()) {
            "down" -> swipe(w / 2, h * 0.72f, w / 2, h * 0.28f, 350)   // see content further down
            "up" -> swipe(w / 2, h * 0.28f, w / 2, h * 0.72f, 350)
            "left" -> swipe(w * 0.8f, h / 2, w * 0.2f, h / 2, 350)
            "right" -> swipe(w * 0.2f, h / 2, w * 0.8f, h / 2, 350)
            else -> false
        }
    }

    // ---- nodes ----
    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val hits = root.findAccessibilityNodeInfosByText(text)
        val n = hits.firstOrNull { it.isVisibleToUser } ?: return false
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect(); n.getBoundsInScreen(r)
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) { n.getChild(i)?.let { c -> findEditable(c)?.let { return it } } }
        return null
    }

    fun typeText(text: String, replace: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        val cur = if (replace || n.isShowingHintText) "" else (n.text?.toString() ?: "")
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun key(name: String): Boolean = when (name.lowercase()) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
        "enter" -> pressEnter()
        else -> false
    }

    fun openApp(name: String): String? {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(i, PackageManager.MATCH_ALL)
        val q = name.lowercase().trim()
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: apps.firstOrNull { it.activityInfo.packageName.lowercase().contains(q) }
            ?: return null
        val launch = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(launch)
        return hit.loadLabel(pm).toString()
    }

    /** Compact text description of what is on screen, with tap coordinates (pixels). */
    fun dumpScreen(max: Int): String {
        val root = rootInActiveWindow ?: return "No active window (screen may be locked or transitioning)"
        val dm = resources.displayMetrics
        val sb = StringBuilder("screen=${dm.widthPixels}x${dm.heightPixels} app=${root.packageName}\n")
        var count = 0
        fun walk(n: AccessibilityNodeInfo) {
            if (count >= max) return
            val label = (n.text ?: n.contentDescription ?: n.hintText)?.toString()?.replace("\n", " ")?.take(80)
            if (n.isVisibleToUser && (label != null || n.isClickable || n.isEditable)) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    sb.append("[${r.centerX()},${r.centerY()}] ")
                    sb.append((n.className?.toString() ?: "").substringAfterLast('.'))
                    if (label != null) sb.append(" \"$label\"")
                    if (n.isClickable) sb.append(" click")
                    if (n.isEditable) sb.append(" edit")
                    if (n.isScrollable) sb.append(" scroll")
                    if (n.isChecked) sb.append(" checked")
                    sb.append('\n'); count++
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return sb.toString()
    }
}
