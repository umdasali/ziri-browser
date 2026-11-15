package com.ziribrowser.passwords

/**
 * The script that watches pages for sign-in forms. It only reports to the app (never to the page), and only from the
 * top-level page. The names of the message channel and of the fill function are random per run, so a page can't
 * call them by accident or by design.
 */
object PageScript {
    fun build(channel: String, fillFn: String): String = TEMPLATE.replace("%CHANNEL%", channel).replace("%FILL%", fillFn)

    private val TEMPLATE = """
(function () {
  if (window.top !== window) return;
  var okPage = location.protocol === 'https:' || location.hostname === 'localhost' || location.hostname === '127.0.0.1';
  if (!okPage) return;
  var post = function (m) { try { %CHANNEL%.postMessage(JSON.stringify(m)); } catch (e) {} };
  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    var s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none';
  }
  function pwFields() {
    return Array.prototype.slice.call(document.querySelectorAll('input[type=password]')).filter(visible);
  }
  function userFieldFor(pw) {
    var scope = pw.form || document;
    var inputs = Array.prototype.slice.call(scope.querySelectorAll('input'));
    for (var j = inputs.indexOf(pw) - 1; j >= 0; j--) {
      var t = (inputs[j].type || 'text').toLowerCase();
      if ((t === 'text' || t === 'email' || t === 'tel' || t === 'url') && visible(inputs[j])) return inputs[j];
    }
    return null;
  }
  var timer = 0;
  function report() { var n = pwFields().length; if (n) post({ t: 'form', n: n }); }
  function schedule() { clearTimeout(timer); timer = setTimeout(report, 400); }
  function capture() {
    var filled = pwFields().filter(function (p) { return p.value; });
    if (!filled.length) return;
    var pw = filled[0];
    var u = userFieldFor(pw);
    post({ t: 'submit', user: u ? u.value : '', pass: pw.value, change: filled.length > 1 });
  }
  document.addEventListener('submit', capture, true);
  document.addEventListener('click', function (e) {
    var b = e.target && e.target.closest ? e.target.closest('button, input[type=submit], input[type=button], [role=button]') : null;
    if (b && pwFields().some(function (p) { return p.value; })) capture();
  }, true);
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && e.target && e.target.type === 'password') capture();
  }, true);
  if (document.readyState !== 'loading') schedule(); else document.addEventListener('DOMContentLoaded', schedule);
  new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
  function setValue(el, v) {
    var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }
  Object.defineProperty(window, '%FILL%', {
    enumerable: false, configurable: false, writable: false,
    value: function (user, pass) {
      var pws = pwFields();
      if (!pws.length) return false;
      var u = userFieldFor(pws[0]);
      if (u && user) setValue(u, user);
      setValue(pws[0], pass);
      return true;
    }
  });
})();
""".trimIndent()
}
