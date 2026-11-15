package com.ziribrowser.privacy

/** Everything the privacy shield can do, as one snapshot the browser applies to its tabs. */
data class PrivacyConfig(
    val fingerprintShield: Boolean = true,
    val blockThirdPartyCookies: Boolean = true,
    val stripTrackingParams: Boolean = true,
    val sendGpc: Boolean = true,
    val safeBrowsing: Boolean = true,
    /** Sites where the fingerprint shield is switched off (it can break pages that draw on a canvas). */
    val exemptHosts: Set<String> = emptySet(),
)

/** Removes tracking tags from links, e.g. `?utm_source=news&fbclid=abc`, while keeping every other parameter. */
object TrackingParams {
    private val exact = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "ttclid", "li_fat_id", "mc_eid", "mc_cid",
        "igshid", "igsh", "_hsenc", "_hsmi", "mkt_tok", "ref_src", "ref_url", "vero_id", "oly_enc_id", "oly_anon_id", "wickedid", "_openstat",
    )
    private val youtubeOnly = setOf("si", "pp", "feature")

    private fun isTracking(key: String, host: String): Boolean {
        val k = key.lowercase()
        if (k.startsWith("utm_") || k in exact) return true
        return k in youtubeOnly && (host == "youtu.be" || host.endsWith("youtube.com"))
    }

    /** Returns [url] without tracking parameters (unchanged if it has none). */
    fun clean(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val hash = url.indexOf('#', q).let { if (it < 0) url.length else it }
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@').substringBefore(':').lowercase()
        val kept = url.substring(q + 1, hash).split('&').filter { part ->
            part.isNotEmpty() && !isTracking(part.substringBefore('='), host)
        }
        val before = url.substring(0, q)
        val after = url.substring(hash)
        return before + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + after
    }
}

/**
 * Makes a browser look different to every site and every session, so a device can't be recognised by its "fingerprint"
 * (canvas, audio, WebGL, hardware details) the way a cookie would recognise it. Values are derived from a random seed that
 * changes each time Ziri starts (and for every private tab), and also differ between sites, so two sites can't link you.
 * Within one site and session the values stay the same, which keeps pages working.
 *
 * Honest limits: this reduces fingerprinting, it doesn't remove it. Fonts, time zone and screen size are left alone
 * because changing them breaks pages.
 */
object FingerprintShield {
    fun script(seed: String, exempt: Set<String>, torMode: Boolean = false): String {
        val exemptJs = exempt.joinToString(",", "[", "]") { h -> "\"" + h.filter { it.isLetterOrDigit() || it == '.' || it == '-' } + "\"" }
        return """
(function(){try{
if($torMode){try{window.RTCPeerConnection=undefined;window.webkitRTCPeerConnection=undefined;window.RTCDataChannel=undefined;}catch(e){}}
var EXEMPT=$exemptJs;
var host=(location.hostname||'').toLowerCase();
for(var q=0;q<EXEMPT.length;q++){if(host===EXEMPT[q]||host.slice(-EXEMPT[q].length-1)==='.'+EXEMPT[q])return;}
var SEED='$seed';
var site=host.split('.').slice(-2).join('.');
function hash(s){var x=2166136261;for(var i=0;i<s.length;i++){x^=s.charCodeAt(i);x=Math.imul(x,16777619);}return x>>>0;}
var base=hash(SEED+'|'+site);
function def(o,p,v){try{Object.defineProperty(o,p,{get:function(){return v},configurable:true})}catch(e){}}
var nav=Navigator.prototype;
def(nav,'hardwareConcurrency',[4,6,8][hash(SEED+site+'c')%3]);
def(nav,'deviceMemory',[4,8][hash(SEED+site+'m')%2]);
def(nav,'languages',['en-US','en']);def(nav,'language','en-US');
def(nav,'getBattery',undefined);def(nav,'connection',undefined);
function noise(d){for(var i=0;i<d.length;i+=4){var x=Math.imul((i>>2)^base,2246822519);x^=x>>>13;x=Math.imul(x,3266489917);x^=x>>>16;if((x>>>0)%250===0){d[i+((x>>>8)%3)]^=1;}}}
var cp=HTMLCanvasElement.prototype,toURL=cp.toDataURL,toBlob=cp.toBlob;
var g2=CanvasRenderingContext2D.prototype,origGet=g2.getImageData;
function copy(c){try{var w=c.width,h=c.height;if(w*h<256||w*h>16777216)return null;
var t=document.createElement('canvas');t.width=w;t.height=h;var x=t.getContext('2d');x.drawImage(c,0,0);
var id=origGet.call(x,0,0,w,h);noise(id.data);x.putImageData(id,0,0);return t;}catch(e){return null}}
cp.toDataURL=function(){var t=copy(this);return toURL.apply(t||this,arguments)};
cp.toBlob=function(){var t=copy(this);return toBlob.apply(t||this,arguments)};
g2.getImageData=function(){var d=origGet.apply(this,arguments);try{noise(d.data)}catch(e){}return d};
function gl(P){var og=P.getParameter;P.getParameter=function(p){if(p===37445)return 'Google Inc.';if(p===37446)return 'ANGLE (Generic Renderer)';return og.apply(this,arguments)}}
if(window.WebGLRenderingContext)gl(WebGLRenderingContext.prototype);
if(window.WebGL2RenderingContext)gl(WebGL2RenderingContext.prototype);
if(window.AudioBuffer){var oc=AudioBuffer.prototype.getChannelData,seen=new WeakSet();
AudioBuffer.prototype.getChannelData=function(){var d=oc.apply(this,arguments);
if(!seen.has(d)){seen.add(d);for(var i=0;i<d.length;i+=100){d[i]+=((hash(site+i)%1000)-500)*1e-9;}}return d};}
}catch(e){}})();
""".trimIndent()
    }

    /** Replaces the device model and Android version in a user-agent string with generic values. */
    fun reduceUserAgent(ua: String): String = ua.replace(Regex("Android [0-9.]+; [^)]*"), "Android 10; K")
}
