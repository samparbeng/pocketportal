package app.pocketportal

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Shows the phone's notifications in the headset while sharing, as toasts the user
 * can reply to (for messaging apps), open, or dismiss.
 *
 * Android only hands notifications to a listener the user has allowed in Settings
 * ("Notification access"); with the Wireless debugging setup done, Pocket Portal can
 * allow itself instead. Nothing is sent unless someone is viewing and
 * [MirrorState.notifications] is on, and only over the PIN-protected, encrypted link.
 */
class HeadsetNotifications : NotificationListenerService() {

    companion object {
        private const val TAG = "HeadsetNotifications"
        private const val MAX_KEPT = 40

        @Volatile
        var instance: HeadsetNotifications? = null
            private set

        /** Where toasts go: set by [MirrorService] while sharing. */
        @Volatile
        var sink: ((String) -> Unit)? = null

        fun component(context: Context) = ComponentName(context, HeadsetNotifications::class.java)

        fun hasAccess(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))

        /** Settings page where the user switches notification access on for Pocket Portal. */
        fun accessSettingsIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
    }

    /** Notifications the headset may act on, by key; oldest dropped first. */
    private val kept = object : LinkedHashMap<String, StatusBarNotification>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StatusBarNotification>?) = size > MAX_KEPT
    }
    /** What each key last showed, so an update that changes nothing doesn't pop up again. */
    private val lastShown = HashMap<String, String>()
    private val icons = HashMap<String, String>()

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val send = sink ?: return
        if (!MirrorState.notifications.value || !worthShowing(sbn, rankingMap)) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val shown = "$title\u0000$text"
        synchronized(kept) {
            kept[sbn.key] = sbn
            if (lastShown[sbn.key] == shown) return
            lastShown[sbn.key] = shown
            if (lastShown.size > MAX_KEPT * 2) lastShown.keys.retainAll(kept.keys)
        }
        val message = JSONObject()
            .put("type", "notif")
            .put("key", sbn.key)
            .put("app", appLabel(sbn.packageName))
            .put("title", title.take(200))
            .put("text", text.take(600))
            .put("reply", replyAction(sbn.notification) != null)
            .put("open", sbn.notification.contentIntent != null)
        appIcon(sbn.packageName)?.let { message.put("icon", it) }
        send(message.toString())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(kept) {
            kept.remove(sbn.key)
            lastShown.remove(sbn.key)
        }
        sink?.invoke(JSONObject().put("type", "notif-gone").put("key", sbn.key).toString())
    }

    /** Skips Pocket Portal's own, ongoing ones (music, navigation), group summaries and silent ones. */
    private fun worthShowing(sbn: StatusBarNotification, rankingMap: RankingMap?): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.isOngoing) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return false
        val ranking = Ranking()
        if (rankingMap?.getRanking(sbn.key, ranking) == true) {
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return false
            if (!ranking.matchesInterruptionFilter()) return false // Do Not Disturb
        }
        return true
    }

    // ---- Actions from the headset ----------------------------------------------------------

    /** `{t:'reply'|'open'|'dismiss', key, s?}` from the viewer page. [launchDisplay] is DeX's, or -1. */
    fun handle(m: JSONObject, launchDisplay: Int) {
        val key = m.optString("key")
        val sbn = synchronized(kept) { kept[key] } ?: return
        runCatching {
            when (m.optString("t")) {
                "reply" -> reply(sbn, m.optString("s").take(2000))
                "open" -> {
                    sbn.notification.contentIntent?.send(this, 0, null, null, null, null, launchOptions(launchDisplay))
                    if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) cancelNotification(key)
                }
                "dismiss" -> cancelNotification(key)
            }
        }.onFailure { Log.w(TAG, "notification action failed", it) }
    }

    private fun reply(sbn: StatusBarNotification, text: String) {
        if (text.isBlank()) return
        val action = replyAction(sbn.notification) ?: return
        val inputs = action.remoteInputs.filter { it.allowFreeFormInput }.toTypedArray()
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        action.actionIntent.send(this, 0, intent)
    }

    private fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.actionIntent != null && a.remoteInputs?.any { it.allowFreeFormInput } == true }

    private fun launchOptions(display: Int): Bundle? {
        val options = ActivityOptions.makeBasic()
        if (display > 0) options.launchDisplayId = display
        if (Build.VERSION.SDK_INT >= 34) {
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        return options.toBundle()
    }

    // ---- Labels and icons ------------------------------------------------------------------

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** The app's icon as a small PNG data URL, cached per app. */
    private fun appIcon(pkg: String): String? = synchronized(icons) {
        icons[pkg] ?: runCatching {
            val drawable = packageManager.getApplicationIcon(pkg)
            val size = 64
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()?.also { icons[pkg] = it }
    }
}
