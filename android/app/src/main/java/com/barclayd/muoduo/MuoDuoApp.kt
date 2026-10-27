package com.barclayd.muoduo

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** One model for the whole app, so the screens, the push service and the alarm all share what the Worker said. */
class MuoDuoApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http by lazy { OkHttpClient() }
    val model by lazy { StatusModel(this, http, scope) }
    val finish by lazy { FinishPreference(this) }

    override fun onCreate() {
        super.onCreate()
        Push.start(this)
    }
}
