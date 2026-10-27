package com.barclayd.muoduo

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class StatusModel(context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    sealed interface Registration {
        data object Pending : Registration
        data object Registered : Registration
        data class Failed(val message: String) : Registration
    }

    var status by mutableStateOf<Status?>(null)
        private set
    /** When [status] arrived, to tell a Worker that has stopped checking from one that's between checks. */
    var receivedAt by mutableStateOf<Instant?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var isWorking by mutableStateOf(false)
        private set
    var registration by mutableStateOf<Registration>(Registration.Pending)
        private set
    var notificationsAllowed by mutableStateOf<Boolean?>(null)

    /** The Worker's checks, newest first, revealed one at a time as they happened. */
    val liveChecks = mutableStateListOf<Status.Check>()
    /** Goes up once per check as it's revealed, to drive pulses. */
    var pulses by mutableIntStateOf(0)
        private set
    /** The same, per area name. */
    val areaPulses = mutableStateMapOf<String, Int>()
    /** Apple's product shots, by URL. */
    val images = mutableStateMapOf<String, ImageBitmap>()

    private val api = ApiClient(http, installID(context))
    private val cacheDir = context.cacheDir
    private var newestCheck: Instant? = null
    /** Bumped by every change the user makes, so a poll that started before it can't undo it. */
    private var generation = 0
    private val loadingImages = mutableSetOf<String>()

    /** Runs [block] on the app's scope, so leaving a screen doesn't cancel a change half made. */
    fun launch(block: suspend StatusModel.() -> Unit) = scope.launch { block() }

    /** Polls until cancelled. The Worker does the checking; this only watches it. */
    suspend fun poll() {
        while (currentCoroutineContext().isActive) {
            val started = generation
            try {
                val status = api.status()
                if (started == generation && !isWorking) absorb(status)
                errorMessage = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                errorMessage = ApiClient.describe(error)
            }
            delay(2_000)
        }
    }

    suspend fun refresh() = perform { it.status() }

    suspend fun setPaused(paused: Boolean) = perform { it.updateConfig(ConfigPatch(paused = paused)) }

    suspend fun setWatching(part: String, watching: Boolean) {
        val parts = status?.device?.parts?.filter { it != part } ?: return
        perform { it.updateDevice(DevicePatch(parts = if (watching) parts + part else parts)) }
    }

    suspend fun setArea(search: String, on: Boolean) {
        val areas = status?.device?.areas?.filter { it != search } ?: return
        perform { it.updateDevice(DevicePatch(areas = if (on) areas + search else areas)) }
    }

    suspend fun setMuted(muted: Boolean) = perform { it.updateDevice(DevicePatch(muted = muted)) }

    suspend fun setAlarm(enabled: Boolean) {
        val alarm = status?.device?.alarm ?: return
        perform { it.updateDevice(DevicePatch(alarm = alarm.copy(enabled = enabled))) }
    }

    suspend fun setAlarmHours(from: Int, to: Int) {
        val alarm = status?.device?.alarm ?: return
        perform { it.updateDevice(DevicePatch(alarm = alarm.copy(from = from, to = to))) }
    }

    suspend fun register(token: String) {
        try {
            absorb(api.registerDevice(token))
            registration = Registration.Registered
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            registration = Registration.Failed(ApiClient.describe(error))
        }
    }

    fun registrationFailed(message: String) {
        registration = Registration.Failed(message)
    }

    /** Returns a message to show the user. */
    suspend fun sendTest(): String = try {
        val result = api.sendTestNotification()
        if (result.delivered) "Sent. It should arrive in a few seconds." else "Not sent: ${result.errors.joinToString(", ")}"
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        ApiClient.describe(error)
    }

    /** Sent after a delay, so there's time to lock the phone and see it ring as it would at 6am. */
    suspend fun sendTestAlarm(seconds: Int): String = try {
        api.sendTestNotification(alarm = true, delaySeconds = seconds)
        "Lock your phone now. The alarm should ring in about $seconds seconds."
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        ApiClient.describe(error)
    }

    fun image(finish: Finish): ImageBitmap? = status?.models(finish)?.firstOrNull()?.let { images[it.imageUrl] }

    private suspend fun perform(request: suspend (ApiClient) -> Status) {
        generation += 1
        isWorking = true
        try {
            absorb(request(api))
            errorMessage = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = ApiClient.describe(error)
        } finally {
            isWorking = false
        }
    }

    private fun absorb(status: Status) {
        this.status = status
        receivedAt = Instant.now()
        loadImages(status.models.map { it.imageUrl }.toSet())

        val newest = status.recentChecks.firstOrNull() ?: return
        val previous = newestCheck
        if (previous == null) {
            // First load: show what's already happened without replaying it.
            liveChecks.clear()
            liveChecks.addAll(status.recentChecks.take(LIVE_CHECK_COUNT))
            newestCheck = newest.at
            return
        }
        newestCheck = maxOf(previous, newest.at)

        // Replay checks made since the last poll at the pace they happened, so each one pulses on its own.
        // After a long gap (the app was in the background) only the last few seconds are replayed.
        val fresh = status.recentChecks.filter { it.at > previous }.reversed()
        val first = fresh.firstOrNull { Duration.between(it.at, newest.at) < Duration.ofSeconds(3) } ?: return
        for (check in fresh) if (check.at < first.at) reveal(check, pulse = false)
        for (check in fresh) if (check.at >= first.at) {
            scope.launch {
                delay(Duration.between(first.at, check.at).toMillis())
                reveal(check, pulse = true)
            }
        }
    }

    private fun reveal(check: Status.Check, pulse: Boolean) {
        if (check in liveChecks) return
        liveChecks.add(0, check)
        while (liveChecks.size > LIVE_CHECK_COUNT) liveChecks.removeAt(liveChecks.lastIndex)
        if (!pulse) return
        pulses += 1
        areaPulses[check.area] = (areaPulses[check.area] ?: 0) + 1
    }

    // Apple's CDN only lets these be cached for about a minute, so they're kept on disk here.
    private fun loadImages(urls: Set<String>) {
        for (url in urls) {
            if (url in images || url in loadingImages) continue
            loadingImages += url
            scope.launch {
                try {
                    val image = withContext(Dispatchers.IO) {
                        val file = File(cacheDir, "${url.substringAfterLast('/')}.png")
                        val data = if (file.exists()) file.readBytes() else {
                            val fetched = runCatching {
                                http.newCall(Request.Builder().url(url).build()).await().use { response ->
                                    if (response.code == 200) response.body.bytes() else null
                                }
                            }.getOrNull() ?: return@withContext null
                            file.writeBytes(fetched)
                            fetched
                        }
                        BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()?.also { it.prepareToDraw() }
                    }
                    if (image != null) images[url] = image
                } finally {
                    loadingImages -= url
                }
            }
        }
    }

    private companion object {
        const val LIVE_CHECK_COUNT = 30

        /** Made up the first time the app runs and kept, so the Worker can tell this phone from others. */
        fun installID(context: Context): String {
            val prefs = context.getSharedPreferences("muoduo", Context.MODE_PRIVATE)
            prefs.getString("deviceID", null)?.let { return it }
            return UUID.randomUUID().toString().also { id -> prefs.edit { putString("deviceID", id) } }
        }
    }
}
