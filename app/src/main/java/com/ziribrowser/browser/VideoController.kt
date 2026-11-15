package com.ziribrowser.browser

import android.webkit.WebView
import com.ziribrowser.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** What the page's main video is doing right now. */
data class VideoState(
    val present: Boolean = false,
    val playing: Boolean = false,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val rate: Float = 1f,
    val width: Int = 0,
    val height: Int = 0,
    val title: String = "",
    val host: String = "",
    /** From a private or Tor tab: the notification must not show what is being watched. */
    val isPrivate: Boolean = false,
)

/**
 * Controls the HTML5 video in the active tab by running small scripts in the page: play/pause, seek,
 * speed, and remembering where the user stopped. Nothing is exposed to the page itself (no JS bridge).
 * Videos inside cross-origin iframes can't be reached by this approach.
 */
class VideoController(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val active: () -> Pair<WebView, TabState>?,
) {
    private val _state = MutableStateFlow(VideoState())
    val state: StateFlow<VideoState> = _state

    // Set by the UI; the polling loop runs only while something needs the video state.
    @Volatile var browserVisible = false
    @Volatile var fullscreen = false
    @Volatile var inBackground = false
    @Volatile var inPip = false
    @Volatile var rememberPositions = true
    /** Called when a playing video should get a media notification. */
    var onPlayingVisible: (() -> Unit)? = null

    private var job: Job? = null
    private var lastSavedAt = 0L
    private var notifiedFor = false

    /** True while a media notification / PiP controls should stay up for the current video. */
    val wantsMediaControls: Boolean
        get() = _state.value.present && (browserVisible || fullscreen || inBackground || inPip)

    fun refresh() {
        val need = browserVisible || fullscreen || inBackground || inPip
        if (need && job == null) job = scope.launch(Dispatchers.Main) { loop() }
        else if (!need) { job?.cancel(); job = null; _state.value = VideoState(); notifiedFor = false }
    }

    private suspend fun loop() {
        while (true) {
            poll()
            delay(if (fullscreen || inBackground || inPip) 1000 else 2500)
        }
    }

    private suspend fun poll() {
        val (wv, tab) = active() ?: run { _state.value = VideoState(); return }
        val raw = eval(wv, STATE_JS)
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (o == null || o.optInt("p") == 0) { _state.value = VideoState(); notifiedFor = false; return }
        val st = VideoState(
            present = true, playing = o.optInt("pl") == 1, position = o.optDouble("t"), duration = o.optDouble("d"),
            rate = o.optDouble("r", 1.0).toFloat(), width = o.optInt("w"), height = o.optInt("h"),
            title = tab.title, host = UrlUtils.host(tab.url), isPrivate = tab.isPrivate,
        )
        _state.value = st
        if (st.playing && (browserVisible || fullscreen) && !notifiedFor) { notifiedFor = true; onPlayingVisible?.invoke() }
        if (!st.playing && !wantsMediaControls) notifiedFor = false
        savePosition(tab, st)
    }

    private fun savePosition(tab: TabState, st: VideoState) {
        if (!rememberPositions || tab.isPrivate || !UrlUtils.isHttp(tab.url) || st.position < MIN_RESUME) return
        val now = System.currentTimeMillis()
        if (now - lastSavedAt < 5_000) return
        lastSavedAt = now
        val key = pageKey(tab.url)
        val finished = st.duration > 0 && st.position > st.duration - FINISHED_TAIL
        scope.launch { settings.setVideoPosition(key, if (finished) null else st.position) }
    }

    /** After a page loads, jump its video to where the user stopped last time (if they got far enough in). */
    fun restore(wv: WebView, tab: TabState) {
        if (!rememberPositions || tab.isPrivate || !UrlUtils.isHttp(tab.url)) return
        scope.launch(Dispatchers.Main) {
            val pos = settings.videoPosition(pageKey(tab.url)) ?: return@launch
            if (pos < MIN_RESUME) return@launch
            wv.evaluateJavascript(restoreJs(pos), null)
        }
    }

    // ---- commands ---------------------------------------------------------------------------
    fun play() = run("v.play()")
    fun pause() = run("v.pause()")
    fun toggle() = run("if(v.paused||v.ended){v.play()}else{v.pause()}")
    fun seekBy(seconds: Double) = run("v.currentTime=Math.max(0,Math.min(isFinite(v.duration)?v.duration:1e9,v.currentTime+($seconds)))")
    fun seekTo(seconds: Double) = run("v.currentTime=Math.max(0,$seconds)")
    fun setSpeed(rate: Float) {
        _state.value = _state.value.copy(rate = rate)
        run("vs.forEach(function(x){x.playbackRate=$rate})")
    }

    private fun run(body: String) {
        val (wv, _) = active() ?: return
        scope.launch(Dispatchers.Main) {
            wv.evaluateJavascript("(function(){$PICK if(!v)return 0;$body;return 1;})()", null)
            // Show the effect immediately instead of waiting for the next poll.
            if (job != null) poll()
        }
    }

    private suspend fun eval(wv: WebView, js: String): String? = suspendCancellableCoroutine { cont ->
        wv.evaluateJavascript(js) { raw ->
            // evaluateJavascript returns a JSON-encoded string, so the JSON we built is wrapped in quotes.
            val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
            if (cont.isActive) cont.resume(text)
        }
    }

    companion object {
        private const val MIN_RESUME = 10.0
        private const val FINISHED_TAIL = 15.0

        /** Same page, ignoring the #fragment. */
        fun pageKey(url: String) = url.substringBefore('#')

        // Pick the video that is playing, otherwise the largest one on the page.
        private const val PICK =
            "var vs=[].slice.call(document.querySelectorAll('video,audio'));" +
                "var v=vs.filter(function(x){return !x.paused&&!x.ended})[0]||" +
                "vs.sort(function(a,b){return (b.videoWidth*b.videoHeight)-(a.videoWidth*a.videoHeight)})[0];"

        private const val STATE_JS =
            "(function(){$PICK if(!v)return JSON.stringify({p:0});" +
                "return JSON.stringify({p:1,pl:!v.paused&&!v.ended,t:v.currentTime,d:isFinite(v.duration)?v.duration:0," +
                "r:v.playbackRate,w:v.videoWidth,h:v.videoHeight});})()"

        private fun restoreJs(pos: Double) =
            "(function(){var n=0,done=false;var iv=setInterval(function(){var v=document.querySelector('video');" +
                "if(v&&v.readyState>=1&&isFinite(v.duration)&&!done){done=true;" +
                "if(v.currentTime<5&&$pos<v.duration-15){v.currentTime=$pos}clearInterval(iv)}" +
                "if(++n>40)clearInterval(iv)},500)})()"
    }
}
