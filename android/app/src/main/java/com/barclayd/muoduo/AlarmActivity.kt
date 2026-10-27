package com.barclayd.muoduo

import android.app.KeyguardManager
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The alarm, over the lock screen, as the iPhone's alarm takes over its screen: the time, what was found,
 * and a big button to stop it. Buy stops it too, and opens the phone's page once the phone is unlocked.
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(TITLE).orEmpty()
        val body = intent.getStringExtra(BODY).orEmpty()
        val url = intent.getStringExtra(URL)
        val finish = (application as MuoDuoApp).finish.value
        setContent {
            MuoDuoTheme(finish) {
                AlarmScreen(finish, title, body, stop = ::stop, buy = url?.let { { buy(it) } })
            }
        }
    }

    private fun stop() {
        Push.stopAlarm(this)
        finish()
    }

    private fun buy(url: String) {
        Push.stopAlarm(this)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            AppleStore.open(this, url)
            return finish()
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                AppleStore.open(this@AlarmActivity, url)
                finish()
            }
        })
    }

    companion object {
        const val TITLE = "title"
        const val BODY = "body"
        const val URL = "url"
    }
}

@Composable
private fun AlarmScreen(finish: Finish, title: String, body: String, stop: () -> Unit, buy: (() -> Unit)?) {
    val haze = rememberHazeState()
    val now by rememberNow(1_000)
    // The phone's own clock, 12 or 24 hour, as the lock screen shows it.
    val pattern = if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm"
    val time = DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(now)
    CompositionLocalProvider(LocalHaze provides haze) {
        Box(Modifier.fillMaxSize().background(ink.background)) {
            Backdrop(finish, Modifier.matchParentSize().hazeSource(haze))
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Symbol(Icons.Filled.Alarm, Type.title2, tint = ink.green)
                Text(time, style = Type.largeTitle.copy(fontSize = 84.sp, lineHeight = 90.sp, fontWeight = FontWeight.Light).monospacedDigit())
                Text(title, style = Type.title2.weight(FontWeight.Bold), textAlign = TextAlign.Center)
                Text(body, color = ink.secondary, style = Type.body, textAlign = TextAlign.Center)
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (buy != null) {
                    GlassButton(onClick = buy, modifier = Modifier.fillMaxWidth(), large = true) { Text("Buy") }
                }
                ProminentButton(onClick = stop, modifier = Modifier.fillMaxWidth(), color = ink.green, large = true) { Text("Stop") }
            }
        }
    }
}
