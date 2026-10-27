package com.barclayd.muoduo

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** [deviceID] is this install's own id, sent with every request, so the Worker can keep this phone's settings apart. */
class ApiClient(private val http: OkHttpClient, private val deviceID: String) {
    class ApiException(message: String) : IOException(message)

    @Serializable private data class ErrorBody(val error: String)
    @Serializable private data class DeviceRegistration(val token: String, val platform: String)
    @Serializable private data class TestRequest(val alarm: Boolean, val delaySeconds: Int)

    /** Short timeout: the app polls this every 2s, and a hung request should read as "offline" quickly. */
    suspend fun status(): Status = send("GET", "status", timeoutSeconds = 8)

    suspend fun registerDevice(token: String): Status =
        send("POST", "devices", json.encodeToString(DeviceRegistration(token, platform = "android")))

    /** This phone's own settings. */
    suspend fun updateDevice(patch: DevicePatch): Status = send("PUT", "device", json.encodeToString(patch))

    /** Settings shared by every phone. Only the owner's key can change them. */
    suspend fun updateConfig(patch: ConfigPatch): Status = send("PUT", "config", json.encodeToString(patch))

    suspend fun checkNow(): Status = send("POST", "check")

    /** With a delay, the Worker answers straight away and sends it later, so it can't say whether it arrived. */
    suspend fun sendTestNotification(alarm: Boolean = false, delaySeconds: Int = 0): TestResult =
        send("POST", "test-notification", json.encodeToString(TestRequest(alarm, delaySeconds)))

    private suspend inline fun <reified T> send(method: String, path: String, body: String? = null, timeoutSeconds: Long = 60): T {
        val base = BuildConfig.API_BASE_URL.toHttpUrlOrNull()
        if (base == null || BuildConfig.API_TOKEN.isEmpty()) throw ApiException("Set MUODUO_API_TOKEN in android/secrets.properties and rebuild.")

        val request = Request.Builder()
            .url(base.resolve(path)!!)
            .header("Authorization", "Bearer ${BuildConfig.API_TOKEN}")
            .header("x-device-id", deviceID)
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .build()
        val call = http.newCall(request).apply { timeout().timeout(timeoutSeconds, TimeUnit.SECONDS) }
        return call.await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val message = runCatching { json.decodeFromString<ErrorBody>(text).error }.getOrNull()
                throw ApiException(message ?: "The Worker returned HTTP ${response.code}.")
            }
            json.decodeFromString<T>(text)
        }
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** Words for a failed request, like the ones iOS gives. */
        fun describe(error: Throwable): String = when (error) {
            is ApiException -> error.message.orEmpty()
            is UnknownHostException -> "The Internet connection appears to be offline."
            is ConnectException -> "Could not connect to the server."
            is InterruptedIOException -> "The request timed out."
            is SerializationException, is IllegalArgumentException -> "The data couldn't be read because it isn't in the correct format."
            else -> error.message ?: error.toString()
        }
    }
}

/** Runs the call, cancelling it if the coroutine is. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { cancel() }
}
