package com.ziribrowser

import android.content.Context
import java.security.SecureRandom

/**
 * A secret that only Ziri's own notifications carry. MainActivity is exported (it has to receive links and files from other apps), so
 * "open this download" extras are honoured only with this token; otherwise any app could make Ziri open a download by guessing its id.
 */
object IntentToken {
    @Volatile private var cached: String? = null

    fun get(context: Context): String = cached ?: synchronized(this) {
        cached ?: run {
            val prefs = context.applicationContext.getSharedPreferences("ziri_intent", Context.MODE_PRIVATE)
            val t = prefs.getString("t", null) ?: ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }.also { prefs.edit().putString("t", it).apply() }
            cached = t
            t
        }
    }
}
