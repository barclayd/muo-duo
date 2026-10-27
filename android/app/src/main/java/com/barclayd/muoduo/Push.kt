package com.barclayd.muoduo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The Worker's alerts, arriving through Firebase Cloud Messaging as data messages so the app shows them
 * itself, whether it's open or not. Stock found in the alarm hours also rings an alarm, through silent
 * mode and Do Not Disturb, as AlarmKit does on the iPhone.
 */
object Push {
    private const val STOCK = "stock"
    private const val ALARM = "alarm"
    private const val ALARM_ID = 1

    private val configured = listOf(
        BuildConfig.FIREBASE_PROJECT_ID,
        BuildConfig.FIREBASE_APP_ID,
        BuildConfig.FIREBASE_API_KEY,
        BuildConfig.FIREBASE_SENDER_ID,
    ).all { it.isNotBlank() }

    /** Firebase is set up here rather than from google-services.json, so its keys can stay out of the repo. */
    fun start(context: Context) {
        channels(context)
        if (configured && FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build(),
            )
        }
    }

    /** Sent on every launch, as the iPhone does: the Worker keeps one entry per token. */
    suspend fun register(model: StatusModel) {
        if (!configured) return model.registrationFailed("Set FIREBASE_… in android/secrets.properties and rebuild.")
        val token = try {
            suspendCancellableCoroutine<String> { done ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful) done.resume(task.result)
                    else done.resumeWithException(task.exception ?: IllegalStateException("Firebase gave no token"))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return model.registrationFailed(error.message ?: "Firebase gave no token")
        }
        model.register(token)
    }

    private fun channels(context: Context) {
        val stock = NotificationChannel(STOCK, "Stock alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "iPhone Duo found in a store near you"
        }
        val alarm = NotificationChannel(ALARM, "Stock alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Stock found in your alarm hours, ringing through silent mode"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800)
            setBypassDnd(true)
        }
        manager(context).createNotificationChannels(listOf(stock, alarm))
        // The checker's health is shown in the app now, never pushed, so its old channel goes.
        manager(context).deleteNotificationChannel("health")
    }

    fun show(context: Context, data: Map<String, String>) {
        val title = data["title"] ?: return
        val body = data["body"].orEmpty()
        val url = data["url"]
        val notification = NotificationCompat.Builder(context, STOCK)
            .setSmallIcon(R.drawable.ic_stat_muoduo)
            .setColor(GREEN)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(data["thread"])
            .setAutoCancel(true)
            .setContentIntent(open(context, url))
            .build()
        // Each alert stays until it's dealt with, as on the iPhone, rather than replacing the last.
        manager(context).notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
        if (data["alarm"] == "true") ring(context, title, body, url)
    }

    /** Straight to the phone's page in the Apple Store app, or the app when there isn't one. */
    private fun open(context: Context, url: String?): PendingIntent {
        val intent = url?.let { Intent(Intent.ACTION_VIEW, it.toUri()) } ?: Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            url.hashCode(),
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ring(context: Context, title: String, body: String, url: String?) {
        // One alarm at a time: more stock while it's ringing shouldn't set off another as soon as it's stopped.
        if (manager(context).activeNotifications.any { it.id == ALARM_ID }) return

        val screen = PendingIntent.getActivity(
            context,
            ALARM_ID,
            Intent(context, AlarmActivity::class.java)
                .putExtra(AlarmActivity.TITLE, title)
                .putExtra(AlarmActivity.BODY, body)
                .putExtra(AlarmActivity.URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(context, ALARM_ID, Intent(context, StopAlarm::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, ALARM)
            .setSmallIcon(R.drawable.ic_stat_muoduo)
            .setColor(GREEN)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .setDeleteIntent(stop)
            .addAction(0, "Stop", stop)
            .build()
        // Rings until it's stopped, rather than once.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        manager(context).notify(ALARM_ID, notification)
    }

    fun stopAlarm(context: Context) = manager(context).cancel(ALARM_ID)

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private const val GREEN = 0xFF34C759.toInt()
}

class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val app = application as MuoDuoApp
        app.scope.launch { app.model.register(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) = Push.show(this, message.data)
}

/** The alarm notification's Stop button. */
class StopAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Push.stopAlarm(context)
}
