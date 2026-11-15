package com.ziribrowser

import android.app.Application
import androidx.annotation.StringRes

class ZiriApp : Application() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
    }

    companion object {
        lateinit var instance: ZiriApp
            private set
    }
}

/**
 * Looks up a translated string outside of composition (toasts, callbacks, background code).
 * Inside composables prefer `stringResource`, which also reacts to configuration changes.
 */
fun ziriString(@StringRes id: Int, vararg args: Any): String = AppLanguage.wrap(ZiriApp.instance).getString(id, *args)
