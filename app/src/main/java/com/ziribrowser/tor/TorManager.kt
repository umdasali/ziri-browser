package com.ziribrowser.tor

import com.ziribrowser.R
import com.ziribrowser.ziriString
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.torproject.jni.TorService
import java.net.InetSocketAddress
import java.net.Proxy

sealed interface TorState {
    data object Off : TorState
    data object Starting : TorState
    data class Connecting(val percent: Int) : TorState
    data class Ready(val socksPort: Int) : TorState
    data class Failed(val reason: String) : TorState
}

/**
 * Runs Tor inside the app (only while a Tor tab is open) and points the web engine at it.
 *
 * Fail-closed on purpose: the moment Tor is wanted, the web engine is told to use a proxy address that goes nowhere,
 * and only switched to Tor's real port once Tor has finished connecting. If anything goes wrong, pages fail to load
 * instead of loading over your normal connection.
 *
 * Android's web engine only allows one proxy setting for the whole app, so while any Tor tab is open every tab uses Tor.
 */
class TorManager(private val app: Application, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<TorState>(TorState.Off)
    val state: StateFlow<TorState> = _state

    private val main = Handler(Looper.getMainLooper())
    private var service: TorService? = null
    private var bound = false
    private var job: Job? = null

    /** True while a Tor tab exists (Tor running or starting). */
    @Volatile var wanted = false
        private set

    val proxySupported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = (binder as TorService.LocalBinder).service }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    /** Turns Tor on or off; call when the first Tor tab opens and the last one closes. */
    fun setWanted(on: Boolean) {
        if (on == wanted) return
        wanted = on
        if (on) start() else stop()
    }

    fun retry() { if (wanted) { stopService(); start() } }

    private fun start() {
        if (!proxySupported) { _state.value = TorState.Failed("This device's web engine can't route pages through Tor."); return }
        _state.value = TorState.Starting
        setWebProxy(DEAD_PORT) // nothing can leak while Tor is still connecting
        runCatching {
            val i = Intent(app, TorService::class.java)
            app.startService(i)
            bound = app.bindService(i, connection, Context.BIND_AUTO_CREATE)
        }.onFailure { _state.value = TorState.Failed(ziriString(R.string.tor_couldn_t_start, it.message.orEmpty())); return }
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            while (wanted) {
                val svc = service
                if (svc != null) {
                    val phase = runCatching { svc.getInfo("status/bootstrap-phase") }.getOrNull().orEmpty()
                    val pct = Regex("PROGRESS=(\\d+)").find(phase)?.groupValues?.get(1)?.toIntOrNull()
                    if (pct != null) {
                        if (pct >= 100) {
                            val port = svc.socksPort
                            if (port > 0) { setWebProxy(port); _state.value = TorState.Ready(port); return@launch }
                        } else _state.value = TorState.Connecting(pct)
                    }
                }
                if (System.currentTimeMillis() - started > 180_000) {
                    _state.value = TorState.Failed(ziriString(R.string.tor_couldn_t_connect_your_network_may_be)); return@launch
                }
                delay(800)
            }
        }
    }

    private fun stop() {
        job?.cancel()
        stopService()
        setWebProxy(null) // back to a normal connection
        _state.value = TorState.Off
    }

    private fun stopService() {
        job?.cancel()
        runCatching { if (bound) app.unbindService(connection) }
        bound = false
        service = null
        runCatching { app.stopService(Intent(app, TorService::class.java)) }
    }

    /** A SOCKS proxy for Ziri's own downloads while Tor is on; null when Tor isn't connected. */
    fun downloadProxy(): Proxy? = (state.value as? TorState.Ready)?.let { Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", it.socksPort)) }

    private fun setWebProxy(port: Int?) {
        if (!proxySupported) return
        main.post {
            val exec = java.util.concurrent.Executor { it.run() }
            runCatching {
                if (port == null) ProxyController.getInstance().clearProxyOverride(exec) {}
                else ProxyController.getInstance().setProxyOverride(ProxyConfig.Builder().addProxyRule("socks5://127.0.0.1:$port").build(), exec) {}
            }
        }
    }

    private companion object { const val DEAD_PORT = 1 }
}
