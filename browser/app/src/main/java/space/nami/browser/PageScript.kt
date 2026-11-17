package space.nami.browser

/**
 * Injected into every page (top frame only). Two jobs:
 * 1) Chrome-style translation: swap text nodes in place, visible part first, keep originals to restore,
 *    and keep translating text that appears later (MutationObserver).
 * 2) Double-tap detection on text / empty space (not on links, buttons, images, inputs).
 * Talks to Kotlin through `NamiBridge` (see Bridge in BrowserTab.kt).
 */
object PageScript {
    /** The script with this launch's bridge key filled in. */
    fun js(key: String) = TEMPLATE.replace("__NAMI_KEY__", key)
        .replace("__NAMI_FNS__", "['translate','idle','doubleTap','scriptVersion','blobBegin','blobChunk','blobEnd','blobFail','claimBlob','downloadHint','forceZoom','fsWrite','fsClose','fsAbort','relayOpen','relayOpenIn','relayBatch','zipMiss','relayFail','relayDone']")

    private val TEMPLATE = """
(function(){
  if (window.top !== window) return;
  if (window.__nami) return;
  // privileged bridge calls carry a per-launch key; methods are captured before page scripts run
  var NB = window.NamiBridge, NK = '__NAMI_KEY__', RA = Reflect.apply, NF = {};
  if (NB) { var NL = __NAMI_FNS__; for (var ni = 0; ni < NL.length; ni++) NF[NL[ni]] = NB[NL[ni]]; }
  function nb(n, a) { var f = NF[n]; if (!f) return undefined; var arr = [NK]; for (var i = 0; i < a.length; i++) arr[i + 1] = a[i]; return RA(f, NB, arr); }
  var N = { on:false, seq:0, pending:{}, nodes:[], queued:new Set(), timer:0, obs:null };

  // ---- look like Tampermonkey to script sites (Greasy Fork checks window.external.Tampermonkey) ----
  try {
    var tm = {
      getVersion: function(cb){ if (cb) cb('5.0'); return Promise.resolve('5.0'); },
      isInstalled: function(name, ns, cb){
        var v = ''; try { v = nb('scriptVersion', [String(name), String(ns || '')]); } catch(e) {}
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
      nb('translate', [id, JSON.stringify(texts)]);
    }
    if (!N.queued.size && !inflight()) nb('idle', []);
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
      if (!nb('blobBegin', [id, href || '', name || '', b.type || '', b.size])) return;
      var CH = 1024 * 1024, off = 0;
      var next = function(){
        if (off >= b.size) { nb('blobEnd', [id]); return; }
        var fr = new FileReader();
        fr.onload = function(){ var s = fr.result; if (!nb('blobChunk', [id, s.substring(s.indexOf(',') + 1)])) return; off += CH; next(); };
        fr.onerror = function(){ nb('blobFail', [id]); };
        fr.readAsDataURL(b.slice(off, off + CH));
      };
      next();
    };
    var known = N.blobs.get(href);
    if (known) go(known); else fetch(href).then(function(r){ return r.blob(); }).then(go).catch(function(){ nb('blobFail', ['']); });
  };
  // ---- downloads a site's service worker makes up (kio.ac: /_service-worker-responsify?id=…) ----
  // Android's downloader can't reach the service worker, so fetch it here and stream it to a file.
  var isSw = function(u){ try { var x = new URL(u, location.href); return x.origin === location.origin && /_service-worker-responsify$/.test(x.pathname); } catch(e) { return false; } };
  // keep the site's service worker awake while it streams (Chrome stops idle workers)
  var poke = function(){ try { var c = navigator.serviceWorker && navigator.serviceWorker.controller; if (c) c.postMessage({ type: 'nami-keepalive' }); } catch(e) {} };
  var newId = function(){ return 'r' + Date.now() + Math.random().toString(36).slice(2); };
  var PW = [], PIPE = window.NamiPipe || null;
  if (PIPE) PIPE.onmessage = function(e){ var w = PW.shift(); if (w) w(e.data === 'ok'); };
  // Writes a response body into the already opened file `id`. progress(bytes) after each piece.
  var streamTo = function(r, id, progress){
    var rd = r.body.getReader(), buf = [], size = 0;
    var viaBridge = function(parts, n){
      var b = new Blob(parts);
      return new Promise(function(ok, bad){ var fr = new FileReader(); fr.onload = function(){ var s = fr.result; if (!nb('fsWrite', [id, s.substring(s.indexOf(',') + 1), -1])) bad(new Error('write')); else { progress(n); ok(); } }; fr.onerror = function(){ bad(fr.error); }; fr.readAsDataURL(b); });
    };
    var flush = function(){
      if (!size) return Promise.resolve();
      var n = size, parts = buf; buf = []; size = 0;
      if (PIPE) {
        var a = new Uint8Array(n), o = 0; parts.forEach(function(c){ a.set(c, o); o += c.byteLength; });
        return new Promise(function(ok, bad){
          PW.push(function(good){ if (good) { progress(n); ok(); } else bad(new Error('pipe')); });
          try { PIPE.postMessage('w:' + id); PIPE.postMessage(a.buffer); } catch(e) { PW.pop(); bad(e); }
        }).catch(function(){ PIPE = null; return viaBridge([a], n); });   // pipe didn't work → old way
      }
      return viaBridge(parts, n);
    };
    // the site stopped sending for a long time → give up instead of hanging forever
    var last = Date.now(), dead = false;
    var watch = setInterval(function(){ if (Date.now() - last > 30000) { dead = true; clearInterval(watch); try { rd.cancel('stalled'); } catch(e) {} } }, 5000);
    var pump = function(){
      return rd.read().then(function(x){
        last = Date.now();
        if (dead) throw new Error('stalled');
        if (x.done) return flush();
        buf.push(x.value); size += x.value.byteLength;
        return (size >= (PIPE ? 4194304 : 1048576) ? flush() : Promise.resolve()).then(pump);
      });
    };
    return pump().then(function(){ clearInterval(watch); }, function(e){ clearInterval(watch); throw dead ? new Error('stalled') : e; });
  };
  var cdName = function(r, fallback){
    var cd = r.headers.get('Content-Disposition') || '', name = fallback || '';
    var m = /filename\*=UTF-8''([^;]+)/i.exec(cd) || /filename="?([^";]+)"?/i.exec(cd);
    if (m) { try { name = decodeURIComponent(m[1]); } catch(e) { name = m[1]; } }
    return name;
  };

  // ---- folder zips: the site asks its service worker to zip files (kio.ac). Big zips stall in every browser,
  // so we remember what was going to be zipped and fetch the files one by one into a folder instead. ----
  var ZIPS = {}, ZIPREQ = {}, lastZip = null, LOG = [];
  var urlIn = function(o, d){
    if (!o || typeof o !== 'object' || d > 2) return '';
    for (var k in o) { try { var v = o[k];
      if (typeof v === 'string' && /url|href|link|src/i.test(k) && /^(https?:|\/)/.test(v)) return v;
      if (v && typeof v === 'object' && !(v instanceof Blob) && !ArrayBuffer.isView(v)) { var r = urlIn(v, d + 1); if (r) return r; }
    } catch(e) {} }
    return '';
  };
  var nameIn = function(o){ return o && (o.name || o.path || o.filename || o.fileName || o.title || (o.file && (o.file.name || o.file.path))) || ''; };
  var zipOf = function(o, d){
    d = d || 0;
    if (!o || typeof o !== 'object' || d > 4 || o instanceof Blob || ArrayBuffer.isView(o) || o instanceof ArrayBuffer) return null;
    if (Array.isArray(o)) {
      if (o.length && o[0] && typeof o[0] === 'object' && urlIn(o[0], 0) && nameIn(o[0])) return { name: '', entries: o };
      for (var i = 0; i < Math.min(o.length, 4); i++) { var a = zipOf(o[i], d + 1); if (a) return a; }
      return null;
    }
    for (var k in o) { try {
      var z = zipOf(o[k], d + 1);
      if (z) { if (!z.name) z.name = String(o.name || o.filename || o.title || ''); return z; }
    } catch(e) {} }
    return null;
  };
  var peek = function(where, m){
    try {
      var s = typeof m === 'string' ? m : JSON.stringify(m, function(k, v){ return (v instanceof Blob) ? '[Blob ' + v.size + ']' : (v && (ArrayBuffer.isView(v) || v instanceof ArrayBuffer)) ? '[bytes]' : (typeof ReadableStream !== 'undefined' && v instanceof ReadableStream) ? '[stream]' : v; });
      if (s && s.length > 4) { LOG.push(where + ': ' + s.slice(0, 1500)); if (LOG.length > 12) LOG.shift(); }
    } catch(e) {}
  };
  var seeZip = function(where, m){
    peek(where, m);
    try { var z = zipOf(typeof m === 'string' ? JSON.parse(m) : m); if (z) { lastZip = { z: z, at: Date.now() }; if (m && m.id) ZIPREQ[m.id] = z; } } catch(e) {}
  };
  try {
    [['sw', window.ServiceWorker], ['port', window.MessagePort], ['bc', window.BroadcastChannel], ['worker', window.Worker]].forEach(function(p){
      var C = p[1];
      if (!C || !C.prototype || !C.prototype.postMessage) return;
      var o = C.prototype.postMessage;
      C.prototype.postMessage = function(m){ seeZip(p[0], m); return o.apply(this, arguments); };
    });
    var oFetch = window.fetch;
    window.fetch = function(u, init){
      try { var s = String(u && u.url || u); if (/\/sw\/|zip/i.test(s) || (init && init.body && typeof init.body === 'string')) seeZip('fetch ' + (init && init.method || 'GET') + ' ' + s, init && init.body || ''); } catch(e) {}
      return oFetch.apply(this, arguments);
    };
    if (navigator.serviceWorker) navigator.serviceWorker.addEventListener('message', function(e){
      try { var d = e.data; peek('from-sw', d); var z = d && d.id && ZIPREQ[d.id], u = z && d.payload && (d.payload.url || d.payload);
        if (z && typeof u === 'string') { ZIPS[new URL(u, location.href).href] = z; delete ZIPREQ[d.id]; } } catch(x) {}
    });
  } catch(e) {}
  var zipFor = function(url){
    var u; try { u = new URL(url, location.href).href; } catch(e) { return null; }
    if (ZIPS[u]) { var z = ZIPS[u]; delete ZIPS[u]; return z; }
    if (lastZip && Date.now() - lastZip.at < 120000 && /zip/i.test(u)) { var l = lastZip.z; lastZip = null; return l; }
    if (/zip/i.test(u)) nb('zipMiss', [LOG.join('\n\n')]);   // couldn't find the file list → let the user send it to us
    return null;
  };
  N.relayZip = function(url, z){
    var parts = function(e){ return String(nameIn(e)).replace(/\\/g, '/').split('/').filter(function(s){ return s && s !== '.' && s !== '..'; }); };
    // the folder: the zip's name, else the entries' own top folder, else the site's name
    var folder = z.name.replace(/\.zip$/i, ''), top = null;
    z.entries.forEach(function(e){ var p = parts(e); var t = p.length > 1 ? p[0] : ''; top = top === null ? t : (top === t ? top : ''); });
    var base = folder && folder !== top ? folder : '';
    var label = folder || top || location.hostname;
    if (!base && !top) base = location.hostname;
    var i = 0, ok = 0, bad = 0;
    var ping = setInterval(poke, 10000);
    nb('relayBatch', [label, z.entries.length, 0, 0, 0]);
    // One file. If the site stalls or the connection drops, ask again from where we stopped (Range);
    // if the site can't resume, start that file over. Up to 5 tries.
    var one = function(e, n){
      var p = {}, src = e.request && e.request.url ? e.request : { url: urlIn(e, 0) };
      Object.keys(src).forEach(function(k){ if (k !== 'url' && k !== 'reuse' && k !== 'signal') p[k] = src[k]; });
      if (p.mode === 'navigate') p.mode = 'same-origin';
      if (e.request && e.request.body == null) delete p.body;
      var path = parts(e); if (base && path.length > 1 && path[0] === base) path.shift();
      var name = path.pop() || ('file' + n), dir = (base ? [base] : []).concat(path).join('/');
      var id = null, got = 0, tries = 5;
      var attempt = function(){
        var q = Object.assign({}, p);
        if (id && got > 0) { var hd = new Headers(p.headers || {}); hd.set('Range', 'bytes=' + got + '-'); q.headers = hd; }
        return fetch(src.url, q).then(function(r){
          if (!r.ok || !r.body) throw new Error('HTTP ' + r.status);
          if (id && got > 0 && r.status !== 206) { nb('fsAbort', [id]); nb('relayDone', ['']); id = null; got = 0; }   // can't resume → over
          if (!id) {
            id = newId();
            if (!nb('relayOpenIn', [id, dir, name, (r.headers.get('Content-Type') || '').split(';')[0], n, z.entries.length])) { id = null; throw new Error('save'); }
          }
          return streamTo(r, id, function(k){ got += k; });
        }).then(function(){ nb('fsClose', [id]); nb('relayDone', ['']); return true; },
                 function(err){
                   if (--tries > 0) return new Promise(function(ok){ setTimeout(ok, 2000); }).then(attempt);
                   if (id) { nb('fsAbort', [id]); nb('relayDone', ['']); }
                   return false;
                 });
      };
      return attempt();
    };
    var next = function(){
      if (i >= z.entries.length) { clearInterval(ping); nb('relayBatch', [label, z.entries.length, ok, bad, 1]); return; }
      var e = z.entries[i++];
      one(e, i).then(function(good){ if (good) ok++; else bad++; next(); });
    };
    next();
  };

  N.relay = function(url, hint){
    var z = zipFor(url);
    if (z) return N.relayZip(url, z);
    var got = 0, opened = false, ping = null;
    fetch(url).then(function(r){
      if (!r.ok || !r.body) throw new Error('HTTP ' + r.status);
      var id = newId();
      if (!nb('relayOpen', [id, cdName(r, hint), (r.headers.get('Content-Type') || '').split(';')[0]])) throw new Error('save');
      opened = true; ping = setInterval(poke, 10000);
      return streamTo(r, id, function(n){ got += n; }).then(function(){
        clearInterval(ping); nb('fsClose', [id]); nb('relayDone', [url]);
      }, function(e){ nb('fsAbort', [id]); throw e; });
    }).catch(function(e){ if (ping) clearInterval(ping); nb('relayFail', [String(e && e.message || e), url, got, opened]); });
  };
  try {
    var oOpen = window.open;
    window.open = function(u){ if (u && isSw(u)) { N.relay(String(u), ''); return null; } return oOpen.apply(window, arguments); };
  } catch(e) {}
  document.addEventListener('click', function(e){
    var a = e.target && e.target.closest && e.target.closest('a[href]');
    if (a && isSw(a.href)) { e.preventDefault(); e.stopImmediatePropagation(); N.relay(a.href, a.getAttribute('download') || ''); }
  }, true);
  function onDownloadLink(a){
    try {
      if (!a || !a.hasAttribute || !a.hasAttribute('download')) return;
      var name = a.getAttribute('download') || '', href = a.href || '';
      if (href.indexOf('blob:') === 0) { nb('claimBlob', [href]); N.saveBlob(href, name); }
      else nb('downloadHint', [href, name]);
    } catch(e) {}
  }
  document.addEventListener('click', function(e){ var a = e.target && e.target.closest && e.target.closest('a[download]'); if (a) onDownloadLink(a); }, true);
  try {
    var oClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function(){ if (this.href && isSw(this.href)) { N.relay(this.href, this.getAttribute('download') || ''); return; } if (!this.isConnected) onDownloadLink(this); return oClick.apply(this, arguments); };
  } catch(e) {}

  // ---- "확대/축소 항상 허용": undo viewport rules that block pinch zoom ----
  function fixViewport(){
    try {
      if (!nb('forceZoom', [])) return;
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

  // ---- what Kotlin may call: frozen, not replaceable by the page; control calls need the key ----
  var API = Object.freeze({
    start: function(k){ if (k === NK) N.start(); },
    stop: function(k){ if (k === NK) N.stop(); },
    done: function(id, arr){ N.done(id, arr); },
    fail: function(id){ N.fail(id); },
    saveBlob: function(k, href, name){ if (k === NK) N.saveBlob(href, name); },
    relay: function(k, url, name){ if (k === NK) N.relay(url, name); }
  });
  try { Object.defineProperty(window, '__nami', { value: API, writable: false, configurable: false }); } catch(e) {}

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
    if (now - lastT < 320 && Math.abs(sx - lx) < 40 && Math.abs(sy - ly) < 40) { lastT = 0; nb('doubleTap', []); }
    else { lastT = now; lx = sx; ly = sy; }
  }, { passive:true, capture:true });
})();
""".trimIndent()
}
