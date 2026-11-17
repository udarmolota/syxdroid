// Injected into mod.io's Songs of Syx pages by WorkshopFragment. Puts a round download button on
// every mod card; a tap adds the mod to the launcher's install queue (through the SyxDroidModio
// bridge), another tap takes it out. The only thing read from mod.io's markup is the mod links
// ("/g/songsofsyx/m/<name>"); a card is the nearest ancestor of such a link that holds a picture.
// mod.io renders its lists on the fly, so a MutationObserver decorates cards as they appear.
// Safe to inject more than once.
(function () {
  if (window.__syxModio) { window.__syxModio.refresh(); return; }
  var bridge = window.SyxDroidModio;
  if (!bridge) return;

  var style = document.createElement('style');
  style.textContent =
    '.syx-q{position:absolute;top:6px;right:6px;z-index:9999;width:40px;height:40px;border-radius:20px;' +
    'border:2px solid #fff;background:rgba(10,10,14,.85);color:#fff;font:bold 22px/36px sans-serif;' +
    'text-align:center;padding:0;margin:0;box-shadow:0 1px 4px rgba(0,0,0,.6);cursor:pointer}' +
    '.syx-q.on{background:#4c9a2a}' +
    '.syx-host{position:relative !important}';
  (document.head || document.documentElement).appendChild(style);

  var MOD = /\/g\/songsofsyx\/m\/([^\/?#]+)/;

  // The mods an element links to (a card links to one, maybe several times: picture, title).
  function modsIn(el) {
    var set = {}, n = 0;
    var ls = el.querySelectorAll('a[href*="/g/songsofsyx/m/"]');
    for (var i = 0; i < ls.length; i++) {
      var m = MOD.exec(ls[i].getAttribute('href') || '');
      if (m && !set[m[1]]) { set[m[1]] = 1; n++; }
    }
    return n;
  }

  // The card: the largest ancestor that still links to this one mod only and holds a picture.
  // Not by width: on a phone mod.io's cards are as wide as the screen.
  function cardOf(a) {
    var el = a, card = null;
    for (var i = 0; i < 8 && el && el !== document.body; i++) {
      if (modsIn(el) > 1) break;                       // past the card: a list of several mods
      if (el.querySelector && el.querySelector('img') && el.offsetHeight > 40) card = el;
      el = el.parentElement;
    }
    return card;
  }

  function titleOf(card, slug) {
    var img = card.querySelector('img[alt]');
    var t = img ? (img.getAttribute('alt') || '').trim() : '';
    if (!t) {
      var lines = (card.innerText || '').split('\n');
      for (var i = 0; i < lines.length; i++) {
        var s = lines[i].trim();
        if (s.length > 1 && !/^add to collection$/i.test(s)) { t = s; break; }
      }
    }
    return t || slug;
  }

  function paint(btn, on) {
    btn.textContent = on ? '✓' : '↓';
    if (on) btn.classList.add('on'); else btn.classList.remove('on');
  }

  function decorate() {
    var here = MOD.exec(location.pathname);
    var links = document.querySelectorAll('a[href*="/g/songsofsyx/m/"]');
    for (var i = 0; i < links.length; i++) {
      var a = links[i];
      var m = MOD.exec(a.getAttribute('href') || '');
      if (!m) continue;
      var slug = m[1];
      if (here && here[1] === slug) continue;   // the mod page itself: the launcher's bar handles it
      var card = cardOf(a);
      if (!card || card.getAttribute('data-syx-mod')) continue;
      card.setAttribute('data-syx-mod', slug);
      card.classList.add('syx-host');
      var btn = document.createElement('button');
      btn.className = 'syx-q';
      btn.setAttribute('data-syx', slug);
      paint(btn, bridge.isQueued(slug));
      (function (btn, card, slug) {
        btn.addEventListener('click', function (e) {
          e.preventDefault();
          e.stopPropagation();
          paint(btn, bridge.toggle(slug, titleOf(card, slug)));
        }, true);
      })(btn, card, slug);
      card.appendChild(btn);
    }
  }

  function refresh() {
    var btns = document.querySelectorAll('button.syx-q');
    for (var i = 0; i < btns.length; i++) paint(btns[i], bridge.isQueued(btns[i].getAttribute('data-syx')));
    decorate();
  }

  var pending = false;
  new MutationObserver(function () {
    if (pending) return;
    pending = true;
    setTimeout(function () { pending = false; decorate(); }, 250);
  }).observe(document.documentElement, { childList: true, subtree: true });

  window.__syxModio = { refresh: refresh };
  decorate();
})();
