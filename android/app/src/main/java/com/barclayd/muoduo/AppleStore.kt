package com.barclayd.muoduo

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri

/**
 * Opens a model's page in the Apple Store app, which handles apple.com/shop links when it's installed,
 * or in the browser when it isn't.
 */
object AppleStore {
    fun open(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w("MuoDuo", "Couldn't open $url", it) }
    }
}
