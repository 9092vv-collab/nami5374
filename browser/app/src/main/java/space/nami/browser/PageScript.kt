package space.nami.browser

/**
 * Injected into every page (top frame only). Two jobs:
 * 1) Chrome-style translation: swap text nodes in place, visible part first, keep originals to restore,
 *    and keep translating text that appears later (MutationObserver).
 * 2) Double-tap detection on text / empty space (not on links, buttons, images, inputs).
 * Talks to Kotlin through `NamiBridge` (see Bridge in BrowserTab.kt).
 */
object PageScript {
    val JS = """
(function(){
  if (window.top !== window) return;
  if (window.__nami) return;
  var N = window.__nami = { on:false, seq:0, pending:{}, nodes:[], queued:new Set(), timer:0, obs:null };

  // ---- look like Tampermonkey to script sites (Greasy Fork checks window.external.Tampermonkey) ----
  try {
    var tm = {
      getVersion: function(cb){ if (cb) cb('5.0'); return Promise.resolve('5.0'); },
      isInstalled: function(name, ns, cb){
        var v = ''; try { v = NamiBridge.scriptVersion(String(name), String(ns || '')); } catch(e) {}
        var r = { installed: !!v, version: v || null, enabled: true };
        if (cb) cb(r); return Promise.resolve(r);
      }
    };
    if (!window.external) window.external = {};
    Object.defineProperty(window.external, 'Tampermonkey', { value: tm, configurable: true });
  } catch(e) {}
  var SKIP = {SCRIPT:1,STYLE:1,NOSCRIPT:1,TEXTAREA:1,INPUT:1,SELECT:1,OPTION:1,CODE:1,KBD:1,SAMP:1,SVG:1,svg:1,MATH:1,TEMPLATE:1,IFRAME:1,CANVAS:1};
  var LETTER = /[A-Za-zÀ-ɏͰ-ϿЀ-ӿ぀-ヿ㐀-鿿฀-๿؀-ۿ]/;
  var MAX_ITEMS = 60, MAX_CHARS = 3500, MAX_INFLIGHT = 3;

  function skipEl(el){
    for (var e = el; e && e !== document.body; e = e.parentElement) {
      if (SKIP[e.tagName]) return true;
      if (e.isContentEditable) return true;
      if (e.getAttribute && (e.getAttribute('translate') === 'no' || (e.classList && e.classList.contains('notranslate')))) return true;
    }
    return false;
  }
  function wanted(t){
    var v = t.nodeValue;
    if (!v || v.length < 1 || !v.trim()) return false;
    if (t.__namiTr !== undefined && t.nodeValue === t.__namiTr) return false;
    if (!LETTER.test(v)) return false;
    return !skipEl(t.parentElement);
  }
  function collect(root, out){
    if (!root) return;
    if (root.nodeType === 3) { if (wanted(root)) out.push(root); return; }
    if (root.nodeType !== 1 && root.nodeType !== 9 && root.nodeType !== 11) return;
    if (root.nodeType === 1 && SKIP[root.tagName]) return;
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var n; while ((n = w.nextNode())) { if (wanted(n)) out.push(n); }
  }
  function visible(t){
    var el = t.parentElement; if (!el) return false;
    var r = el.getBoundingClientRect();
    var h = window.innerHeight;
    return r.bottom > -h * 0.5 && r.top < h * 1.5 && (r.width > 0 || r.height > 0);
  }
  function enqueue(list){
    for (var i = 0; i < list.length; i++) N.queued.add(list[i]);
    pump();
  }
  function inflight(){ var c = 0; for (var k in N.pending) c++; return c; }
  function pump(){
    if (!N.on) return;
    while (inflight() < MAX_INFLIGHT && N.queued.size) {
      var all = Array.from(N.queued);
      var vis = all.filter(visible);
      var pick = (vis.length ? vis : all);
      var batch = [], texts = [], chars = 0;
      for (var i = 0; i < pick.length && batch.length < MAX_ITEMS; i++) {
        var t = pick[i]; N.queued.delete(t);
        if (!t.isConnected || !wanted(t)) continue;
        var v = t.nodeValue, core = v.trim();
        if (chars + core.length > MAX_CHARS && batch.length) { N.queued.add(t); break; }
        if (t.__namiOrig === undefined) t.__namiOrig = v;
        batch.push(t); texts.push(core.replace(/\s+/g, ' ')); chars += core.length;
      }
      if (!batch.length) continue;
      var id = ++N.seq; N.pending[id] = batch;
      NamiBridge.translate(id, JSON.stringify(texts));
    }
    if (!N.queued.size && !inflight()) NamiBridge.idle();
  }
  N.done = function(id, arr){
    var batch = N.pending[id]; delete N.pending[id];
    if (!batch || !N.on) { pump(); return; }
    for (var i = 0; i < batch.length; i++) {
      var t = batch[i], tr = arr[i];
      if (tr == null || !t.isConnected) continue;
      var o = t.__namiOrig; if (t.nodeValue !== o) continue;
      var lead = o.match(/^\s*/)[0], trail = o.match(/\s*$/)[0];
      t.__namiTr = lead + tr + trail;
      t.nodeValue = t.__namiTr;
      N.nodes.push(t);
    }
    pump();
  };
  N.fail = function(id){ delete N.pending[id]; pump(); };
  function scan(){ var out = []; collect(document.body, out); enqueue(out); }
  N.start = function(){
    if (N.on) { scan(); return; }
    N.on = true; scan();
    if (!N.obs) N.obs = new MutationObserver(function(muts){
      if (!N.on) return;
      var out = [];
      for (var i = 0; i < muts.length; i++) {
        var m = muts[i];
        if (m.type === 'characterData') { var t = m.target; if (t.__namiTr !== undefined && t.nodeValue !== t.__namiTr) t.__namiOrig = t.nodeValue; if (wanted(t)) out.push(t); }
        else for (var j = 0; j < m.addedNodes.length; j++) collect(m.addedNodes[j], out);
      }
      if (out.length) { clearTimeout(N.timer); var copy = out; N.timer = setTimeout(function(){ enqueue(copy); }, 250); }
    });
    N.obs.observe(document.body, { childList:true, subtree:true, characterData:true });
    var lastY = window.scrollY;
    window.addEventListener('scroll', function(){ if (N.on && Math.abs(window.scrollY - lastY) > 200) { lastY = window.scrollY; pump(); } }, { passive:true });
  };
  N.stop = function(){
    N.on = false; N.queued.clear(); N.pending = {};
    if (N.obs) { N.obs.disconnect(); N.obs = null; }
    for (var i = 0; i < N.nodes.length; i++) {
      var t = N.nodes[i];
      if (t.nodeValue === t.__namiTr) t.nodeValue = t.__namiOrig;
      t.__namiTr = undefined; t.__namiOrig = undefined;
    }
    N.nodes = [];
  };

  // ---- downloads: remember <a download="name">, save blob: files before the page revokes them ----
  N.blobs = new Map();
  try {
    var oCreate = URL.createObjectURL;
    URL.createObjectURL = function(o){ var u = oCreate.apply(URL, arguments); try { if (o instanceof Blob) { N.blobs.set(u, o); setTimeout(function(){ N.blobs.delete(u); }, 120000); } } catch(e) {} return u; };
  } catch(e) {}
  N.saveBlob = function(href, name){
    var go = function(b){
      var id = 'b' + Date.now() + Math.random().toString(36).slice(2);
      if (!NamiBridge.blobBegin(id, href || '', name || '', b.type || '', b.size)) return;
      var CH = 1024 * 1024, off = 0;
      var next = function(){
        if (off >= b.size) { NamiBridge.blobEnd(id); return; }
        var fr = new FileReader();
        fr.onload = function(){ var s = fr.result; if (!NamiBridge.blobChunk(id, s.substring(s.indexOf(',') + 1))) return; off += CH; next(); };
        fr.onerror = function(){ NamiBridge.blobFail(id); };
        fr.readAsDataURL(b.slice(off, off + CH));
      };
      next();
    };
    var known = N.blobs.get(href);
    if (known) go(known); else fetch(href).then(function(r){ return r.blob(); }).then(go).catch(function(){ NamiBridge.blobFail(''); });
  };
  function onDownloadLink(a){
    try {
      if (!a || !a.hasAttribute || !a.hasAttribute('download')) return;
      var name = a.getAttribute('download') || '', href = a.href || '';
      if (href.indexOf('blob:') === 0) { NamiBridge.claimBlob(href); N.saveBlob(href, name); }
      else NamiBridge.downloadHint(href, name);
    } catch(e) {}
  }
  document.addEventListener('click', function(e){ var a = e.target && e.target.closest && e.target.closest('a[download]'); if (a) onDownloadLink(a); }, true);
  try {
    var oClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function(){ if (!this.isConnected) onDownloadLink(this); return oClick.apply(this, arguments); };
  } catch(e) {}

  // ---- "확대/축소 항상 허용": undo viewport rules that block pinch zoom ----
  function fixViewport(){
    try {
      if (!NamiBridge.forceZoom()) return;
      var ms = document.querySelectorAll('meta[name=viewport]');
      for (var i = 0; i < ms.length; i++) {
        var c = ms[i].getAttribute('content') || '';
        var n = c.replace(/(^|,)\s*(user-scalable|maximum-scale)\s*=\s*[^,]*/gi, '').replace(/^\s*,\s*/, '');
        if (n !== c || !/user-scalable/i.test(c)) ms[i].setAttribute('content', (n ? n + ', ' : '') + 'user-scalable=yes, maximum-scale=10');
      }
    } catch(e) {}
  }
  document.addEventListener('DOMContentLoaded', fixViewport);
  setTimeout(fixViewport, 1500);

  // ---- double tap on text / empty space ----
  var sx = 0, sy = 0, moved = false, multi = false, lastT = 0, lx = 0, ly = 0;
  var NOTAP = 'a,button,input,textarea,select,label,img,video,audio,canvas,summary,[role=button],[onclick],[contenteditable=""],[contenteditable=true]';
  document.addEventListener('touchstart', function(e){
    multi = e.touches.length > 1; moved = false;
    var t = e.touches[0]; sx = t.clientX; sy = t.clientY;
  }, { passive:true, capture:true });
  document.addEventListener('touchmove', function(e){
    var t = e.touches[0]; if (Math.abs(t.clientX - sx) > 12 || Math.abs(t.clientY - sy) > 12) moved = true;
  }, { passive:true, capture:true });
  document.addEventListener('touchend', function(e){
    if (e.touches.length || multi || moved) { lastT = 0; return; }
    var el = e.target;
    if (el && el.closest && el.closest(NOTAP)) { lastT = 0; return; }
    var sel = window.getSelection && window.getSelection();
    if (sel && String(sel).length) { lastT = 0; return; }
    var now = Date.now();
    if (now - lastT < 320 && Math.abs(sx - lx) < 40 && Math.abs(sy - ly) < 40) { lastT = 0; NamiBridge.doubleTap(); }
    else { lastT = now; lx = sx; ly = sy; }
  }, { passive:true, capture:true });
})();
""".trimIndent()
}
