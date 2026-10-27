package com.barclayd.muoduo

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect

/** What the phone lets the app do. Each is read again whenever the app comes back to the front. */
object Permissions {
    fun notifications(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Whether an alarm can take over the screen, as an alarm clock does. Before Android 14 any app that
     * asks for it in its manifest can.
     */
    fun fullScreen(context: Context) =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun openNotificationSettings(context: Context) = open(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    fun openFullScreenSettings(context: Context) = open(
        context,
        if (Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri())
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
        },
    )

    private fun open(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w("MuoDuo", "Couldn't open settings", it) }
    }
}

/** [read], kept fresh: read again each time the app resumes, since the answer changes in Settings. */
@Composable
fun <T> rememberOnResume(read: (Context) -> T): T {
    val context = LocalContext.current
    var value by remember { mutableStateOf(read(context)) }
    LifecycleResumeEffect(Unit) {
        value = read(context)
        onPauseOrDispose {}
    }
    return value
}
