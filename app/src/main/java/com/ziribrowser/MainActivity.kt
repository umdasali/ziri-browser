package com.ziribrowser

import com.ziribrowser.R
import com.ziribrowser.ziriString
import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.util.Rational
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.toArgb
import com.ziribrowser.ui.theme.themeBackground
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.ziribrowser.browser.UrlUtils
import com.ziribrowser.data.AppFont
import com.ziribrowser.data.TextSize
import com.ziribrowser.data.ThemeColor
import com.ziribrowser.data.ThemeMode
import com.ziribrowser.ui.theme.isDark
import com.ziribrowser.ui.nav.ZiriNavHost
import com.ziribrowser.ui.theme.ZiriTheme

class MainActivity : androidx.fragment.app.FragmentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    private val container get() = (application as ZiriApp).container
    private var pendingChooser: ValueCallback<Array<Uri>>? = null

    private val chooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pendingChooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        pendingChooser = null
    }
    private var autoPip = true
    private var pendingPermission: ((Boolean) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pendingPermission?.invoke(result.isNotEmpty() && result.values.all { it })
        pendingPermission = null
    }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* downloads work either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        container.tabs.fileChooser = { callback, intent ->
            pendingChooser?.onReceiveValue(null) // a new request replaces any unanswered one
            pendingChooser = callback
            chooserLauncher.launch(intent)
            true
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        if (savedInstanceState == null) handleIntent(intent)

        container.enterPip = ::enterPip
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Keep the PiP window's shape, buttons and auto-enter flag in step with the video.
        lifecycleScope.launch {
            combine(container.settings.autoPip, container.tabs.customView, container.tabs.video.state) { pip, cv, st -> Triple(pip, cv != null, st) }
                .collect { (pip, fullscreen, st) ->
                    autoPip = pip
                    runCatching { setPictureInPictureParams(pipParams(autoEnter = pip && fullscreen && st.playing)) }
                }
        }

        container.tabs.osPermissionRequester = { permissions, callback ->
            pendingPermission?.invoke(false) // a new request replaces any unanswered one
            pendingPermission = callback
            permissionLauncher.launch(permissions)
        }

        // Paint the window in the saved theme straight away, so there is no white/dark flash on start.
        val bootMode = container.settings.bootMode()
        val bootColor = container.settings.bootColor()
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bootDark = when (bootMode) { ThemeMode.LIGHT -> false; ThemeMode.DARK -> true; ThemeMode.SYSTEM -> systemDark }
        val bootAmoled = container.settings.bootAmoled()
        window.setBackgroundDrawable(ColorDrawable(themeBackground(bootColor, bootDark, bootAmoled).toArgb()))

        setContent {
            val mode by remember { container.settings.themeMode }.collectAsState(initial = bootMode)
            val color by remember { container.settings.themeColor }.collectAsState(initial = bootColor)
            val font by remember { container.settings.appFont }.collectAsState(initial = AppFont.DEFAULT)
            val textSize by remember { container.settings.textSize }.collectAsState(initial = TextSize.DEFAULT)
            val amoled by remember { container.settings.amoled }.collectAsState(initial = bootAmoled)
            val darkWeb by remember { container.settings.darkWeb }.collectAsState(initial = false)

            // Status/navigation bar icons must follow the app's mode, not the phone's.
            val forcedDark by remember { container.forceDarkBars }.collectAsState()
            val dark = isDark(mode) || forcedDark
            // Private tab on screen: hide it from the recent-apps thumbnail and block screenshots.
            DisposableEffect(forcedDark) {
                if (forcedDark) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                onDispose {}
            }
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            // The window behind Compose follows live theme changes too, so it never shows a stale colour while resizing/rotating.
            val appDark = isDark(mode)
            SideEffect { window.setBackgroundDrawable(ColorDrawable(themeBackground(color, appDark, amoled).toArgb())) }
            // Web pages are darkened only while the app itself is dark.
            LaunchedEffect(appDark, darkWeb) { container.tabs.setDarkWeb(appDark && darkWeb) }
            ZiriTheme(mode, color, font, textSize, amoled) { ZiriNavHost(container) }
        }
    }

    // ---- Picture-in-Picture --------------------------------------------------------------------
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val video = container.tabs.video
            val pl = container.players.player?.takeIf { container.players.screenOpen }
            if (pl != null) {
                // The full-screen player is the one in the little window.
                when (intent.getStringExtra(EXTRA_PIP_ACTION)) {
                    "toggle" -> if (pl.isPlaying) pl.pause() else pl.play()
                    "back" -> pl.seekTo((pl.currentPosition - 10_000).coerceAtLeast(0))
                    "forward" -> pl.seekTo(pl.currentPosition + 10_000)
                }
                runCatching { setPictureInPictureParams(pipParams(false)) }
                return
            }
            when (intent.getStringExtra(EXTRA_PIP_ACTION)) {
                "toggle" -> video.toggle()
                "back" -> video.seekBy(-10.0)
                "forward" -> video.seekBy(10.0)
            }
        }
    }

    private fun pipAction(res: Int, label: String, action: String, code: Int) = RemoteAction(
        Icon.createWithResource(this, res), label, label,
        PendingIntent.getBroadcast(
            this, code, Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_PIP_ACTION, action), PendingIntent.FLAG_IMMUTABLE,
        ),
    )

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        val st = container.tabs.video.state.value
        val pl = container.players.player?.takeIf { container.players.screenOpen }
        val playing = pl?.isPlaying ?: st.playing
        // Window shape follows the video (clamped to what Android allows).
        val vs = pl?.videoSize
        val ratio = if (pl != null) {
            if (vs != null && vs.width > 0 && vs.height > 0) (vs.width * vs.pixelWidthHeightRatio / vs.height).coerceIn(0.5f, 2.39f) else 16f / 9f
        } else if (st.width > 0 && st.height > 0) (st.width.toFloat() / st.height).coerceIn(0.5f, 2.39f) else 16f / 9f
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setActions(
                listOf(
                    pipAction(android.R.drawable.ic_media_rew, ziriString(R.string.back_10_seconds), "back", 1),
                    if (playing) pipAction(android.R.drawable.ic_media_pause, ziriString(R.string.pause), "toggle", 2)
                    else pipAction(android.R.drawable.ic_media_play, ziriString(R.string.play), "toggle", 2),
                    pipAction(android.R.drawable.ic_media_ff, ziriString(R.string.forward_10_seconds), "forward", 3),
                ),
            )
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(false)
        return b.build()
    }

    private fun enterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { enterPictureInPictureMode(pipParams(false)) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP by itself (auto-enter); older versions need this nudge.
        if (Build.VERSION.SDK_INT < 31 && autoPip && container.tabs.customView.value != null) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        container.pipMode.value = isInPictureInPictureMode
        container.tabs.video.inPip = isInPictureInPictureMode
        container.tabs.video.refresh()
    }

    override fun onStart() {
        super.onStart()
        container.tabs.video.inBackground = false
        container.tabs.video.refresh()
    }

    override fun onStop() {
        super.onStop()
        // Keep tracking the video so the notification stays correct while the app is in the background.
        container.tabs.video.inBackground = true
        container.tabs.video.refresh()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        container.enterPip = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) container.navigate("downloads")
        intent.getLongExtra(EXTRA_VIEW_DOWNLOAD, -1L).takeIf { it >= 0 }?.let { container.navigate("viewer/$it") }
        val data = intent.data?.toString()
        if (intent.action == Intent.ACTION_VIEW && UrlUtils.isHttp(data)) {
            container.tabs.openTab(data)
            container.navigate("browser")
        }
        // A video or audio file shared from another app or a file manager opens in Ziri's player.
        val type = intent.type.orEmpty()
        val media = intent.data
        if (intent.action == Intent.ACTION_VIEW && media != null && media.scheme == "content" && (type.startsWith("video/") || type.startsWith("audio/"))) {
            val name = runCatching {
                contentResolver.query(media, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: media.lastPathSegment.orEmpty()
            container.navigate("play/${Uri.encode(media.toString())}?title=${Uri.encode(name)}")
        }
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
        const val EXTRA_VIEW_DOWNLOAD = "view_download"
        private const val ACTION_PIP = "com.ziribrowser.PIP_CONTROL"
        private const val EXTRA_PIP_ACTION = "pip_action"
    }
}
