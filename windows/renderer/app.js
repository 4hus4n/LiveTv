'use strict';

/* ================= Helpers ================= */

const $ = (s) => document.querySelector(s);
const DEFAULT_PLAYLIST = 'https://hilaytv.xyz/play.m3u';
const FAV = '__fav__';
const ALL = '__all__';

const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : JSON.parse(v); } catch (_) { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (_) {} },
};

const S = {
  url: store.get('playlistUrl', DEFAULT_PLAYLIST),
  favs: new Set(store.get('favs', [])),
  group: store.get('lastGroup', null),
  preview: store.get('livePreview', true),
  volume: store.get('volume', 1),
  muted: store.get('muted', false),
  channels: [],
  groups: [],
  list: [],
  query: '',
};

const groupLabel = (g) => (g === FAV ? 'Favourites' : g === ALL ? 'All channels' : g);
const isFav = (ch) => S.favs.has(ch.key);
const initials = (name) =>
  (name.split(/[\s\-_]+/).filter(Boolean).slice(0, 2).map((w) => w[0].toUpperCase()).join('') || 'TV');

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function toggleFav(ch) {
  const added = !isFav(ch);
  if (added) S.favs.add(ch.key); else S.favs.delete(ch.key);
  store.set('favs', [...S.favs]);
  toast(added ? `Added ${ch.name} to favourites` : `Removed ${ch.name} from favourites`);
  return added;
}

let toastTimer = 0;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden'), 2600);
}

/** Shows a logo in `img`, with initials in `ini` as the fallback. */
function setLogo(img, ini, ch) {
  ini.textContent = initials(ch.name);
  ini.style.visibility = 'visible';
  img.onload = () => { ini.style.visibility = 'hidden'; img.style.visibility = 'visible'; };
  img.onerror = () => { img.style.visibility = 'hidden'; ini.style.visibility = 'visible'; };
  img.style.visibility = 'hidden';
  if (ch.logo) img.src = ch.logo; else img.removeAttribute('src');
}

/* ================= Stream engine ================= */

/** Plays a channel in a <video>, trying HLS, MPEG-TS, DASH and native playback in turn. */
class Engine {
  constructor(video, { onError, onPlaying } = {}) {
    this.video = video;
    this.onError = onError;
    this.onPlaying = onPlaying;
    this.token = 0;
    this.queue = [];
    this.mode = null;
    this.ch = null;
    video.addEventListener('playing', () => {
      clearTimeout(this.timer);
      if (this.ch && this.onPlaying) this.onPlaying();
    });
    video.addEventListener('error', () => {
      if (this.mode === 'native' && video.getAttribute('src')) this.fail(this.token);
    });
  }

  plan(url) {
    const u = url.toLowerCase().split('?')[0];
    if (u.includes('.m3u8')) return ['hls', 'native'];
    if (u.endsWith('.mpd')) return ['dash'];
    if (/\.(mp4|mkv|webm|mov|m4v|mp3|aac)$/.test(u)) return ['native'];
    if (u.endsWith('.ts')) return ['mpegts', 'hls'];
    return ['hls', 'mpegts', 'native'];
  }

  async play(ch) {
    this.stop();
    this.ch = ch;
    const t = ++this.token;
    this.queue = this.plan(ch.url);
    try {
      if (window.api) await window.api.setStream({ ua: ch.ua, referrer: ch.referrer, headers: ch.headers });
    } catch (_) {}
    if (t === this.token) this.next(t);
  }

  next(t) {
    if (t !== this.token) return;
    this.teardown();
    const mode = this.queue.shift();
    if (!mode) { this.mode = null; if (this.onError) this.onError(); return; }
    this.mode = mode;
    this.timer = setTimeout(() => this.fail(t), 20000);
    try { this['start_' + mode](t); } catch (_) { this.fail(t); }
  }

  fail(t) {
    if (t !== this.token) return;
    clearTimeout(this.timer);
    this.next(t);
  }

  start_hls(t) {
    if (!window.Hls || !Hls.isSupported()) return this.fail(t);
    const h = new Hls({
      enableWorker: true,
      backBufferLength: 30,
      liveDurationInfinity: true,
      manifestLoadingMaxRetry: 2,
      levelLoadingMaxRetry: 2,
      fragLoadingMaxRetry: 4,
    });
    this.hls = h;
    let recovered = false;
    h.on(Hls.Events.ERROR, (_e, d) => {
      if (t !== this.token || !d.fatal) return;
      if (d.type === Hls.ErrorTypes.MEDIA_ERROR && !recovered) { recovered = true; h.recoverMediaError(); return; }
      // Defer: destroying hls.js inside its own error callback breaks it
      setTimeout(() => this.fail(t), 0);
    });
    h.on(Hls.Events.MANIFEST_PARSED, () => { if (t === this.token) this.video.play().catch(() => {}); });
    h.loadSource(this.ch.url);
    h.attachMedia(this.video);
  }

  start_mpegts(t) {
    if (!window.mpegts || !mpegts.isSupported()) return this.fail(t);
    const p = mpegts.createPlayer(
      { type: 'mpegts', isLive: true, url: this.ch.url },
      { enableWorker: true, liveBufferLatencyChasing: true, lazyLoad: false }
    );
    this.mts = p;
    p.on(mpegts.Events.ERROR, () => this.fail(t));
    p.attachMediaElement(this.video);
    p.load();
    const r = p.play();
    if (r && r.catch) r.catch(() => {});
  }

  start_dash(t) {
    if (!window.dashjs) return this.fail(t);
    const p = dashjs.MediaPlayer().create();
    this.dash = p;
    p.on('error', () => this.fail(t));
    p.initialize(this.video, this.ch.url, true);
  }

  start_native() {
    this.video.src = this.ch.url;
    this.video.play().catch(() => {});
  }

  teardown() {
    clearTimeout(this.timer);
    if (this.hls) { try { this.hls.destroy(); } catch (_) {} this.hls = null; }
    if (this.mts) {
      try { this.mts.pause(); this.mts.unload(); this.mts.detachMediaElement(); this.mts.destroy(); } catch (_) {}
      this.mts = null;
    }
    if (this.dash) { try { this.dash.reset(); } catch (_) {} this.dash = null; }
    const v = this.video;
    if (v.getAttribute('src') || v.srcObject) {
      v.removeAttribute('src');
      try { v.load(); } catch (_) {}
    }
  }

  stop() {
    this.token++;
    this.teardown();
    this.mode = null;
    this.ch = null;
  }
}

/* ================= Home ================= */

const groupsEl = $('#groups');
const gridEl = $('#grid');
const previewVideo = $('#previewVideo');
const previewEngine = new Engine(previewVideo, {
  onPlaying: () => previewVideo.classList.add('on'),
  onError: () => previewVideo.classList.remove('on'),
});
let previewTimer = 0;
let heroIndex = -1;
let heroPreviewing = false;

async function load() {
  stopPreview();
  $('#loading').classList.remove('hidden');
  $('#status').textContent = 'Loading playlist…';
  $('#empty').classList.add('hidden');
  let res;
  try {
    res = await window.api.loadPlaylist(S.url);
  } catch (e) {
    res = { channels: [], error: String(e) };
  }
  $('#loading').classList.add('hidden');
  S.channels = res.channels || [];

  if (!S.channels.length) {
    S.groups = [];
    groupsEl.innerHTML = '';
    gridEl.innerHTML = '';
    $('#groupTitle').textContent = '';
    $('#status').textContent = "Couldn't load playlist";
    $('#heroName').textContent = 'No channels';
    $('#heroMeta').textContent = '';
    showEmpty(`Couldn't load the playlist.\n${res.error || ''}\n\nCheck the URL in Settings, then press Reload.`);
    return;
  }
  if (res.fromCache) toast(`Offline · using saved playlist (${res.error})`);

  S.groups = [FAV, ALL, ...new Set(S.channels.map((c) => c.group))];
  const saved = S.group;
  S.group = (saved && S.groups.includes(saved) && (saved !== FAV || S.favs.size)) ? saved
    : (S.favs.size ? FAV : ALL);
  renderGroups();
  renderGrid();
}

function renderGroups() {
  groupsEl.innerHTML = S.groups.map((g, i) => {
    const icon = g === FAV ? '#i-star' : g === ALL ? '#i-grid' : '#i-tv';
    return `<button class="ri${g === S.group ? ' active' : ''}" data-g="${i}" title="${esc(groupLabel(g))}">` +
      `<svg class="${g === FAV ? 'fav-ic' : ''}"><use href="${icon}"/></svg><span>${esc(groupLabel(g))}</span></button>`;
  }).join('');
}

function selectGroup(g) {
  S.group = g;
  store.set('lastGroup', g);
  if (S.query) { S.query = ''; $('#search').value = ''; }
  for (const b of groupsEl.children) b.classList.toggle('active', S.groups[+b.dataset.g] === g);
  renderGrid();
}

function currentList() {
  if (S.query) {
    const q = S.query.toLowerCase();
    return S.channels.filter((c) => c.name.toLowerCase().includes(q) || c.group.toLowerCase().includes(q));
  }
  if (S.group === FAV) return S.channels.filter(isFav);
  if (S.group === ALL) return S.channels;
  return S.channels.filter((c) => c.group === S.group);
}

function showEmpty(text) {
  const e = $('#empty');
  e.textContent = text;
  e.classList.remove('hidden');
}

function renderGrid(focusKey) {
  stopPreview();
  S.list = currentList();
  $('#groupTitle').textContent = S.query ? `Results for “${S.query}”` : groupLabel(S.group);
  $('#status').textContent = `${S.list.length} channel${S.list.length === 1 ? '' : 's'}`;
  gridEl.scrollTop = 0;

  if (!S.list.length) {
    gridEl.innerHTML = '';
    showEmpty(S.query ? 'No channels match your search.'
      : S.group === FAV ? 'No favourites yet.\nClick ★ on a channel, right-click it, or press S.'
        : 'No channels in this group.');
    return;
  }
  $('#empty').classList.add('hidden');

  const frag = document.createDocumentFragment();
  S.list.forEach((ch, i) => {
    const b = document.createElement('button');
    b.className = 'tile';
    b.dataset.i = i;
    b.title = ch.name;
    b.innerHTML =
      `<div class="thumb"><span class="ini"></span><img loading="lazy" alt=""></div>` +
      `<div class="nm"></div>` +
      `<span class="fav${isFav(ch) ? ' on' : ''}" title="Favourite"><svg><use href="${isFav(ch) ? '#i-star' : '#i-star-o'}"/></svg></span>`;
    b.querySelector('.nm').textContent = ch.name;
    setLogo(b.querySelector('img'), b.querySelector('.ini'), ch);
    frag.appendChild(b);
  });
  gridEl.replaceChildren(frag);

  let idx = 0;
  if (focusKey) {
    const k = S.list.findIndex((c) => c.key === focusKey);
    if (k >= 0) {
      idx = k;
      const t = gridEl.children[k];
      t.scrollIntoView({ block: 'center' });
      t.focus({ preventScroll: true });
    }
  }
  setHero(idx, false);
}

function refreshTileFav(i) {
  const t = gridEl.children[i];
  const ch = S.list[i];
  if (!t || !ch) return;
  const f = t.querySelector('.fav');
  f.classList.toggle('on', isFav(ch));
  f.querySelector('use').setAttribute('href', isFav(ch) ? '#i-star' : '#i-star-o');
}

function favFromGrid(i) {
  const ch = S.list[i];
  if (!ch) return;
  toggleFav(ch);
  if (S.group === FAV && !S.query) {
    renderGrid();
    const t = gridEl.children[Math.min(i, gridEl.children.length - 1)];
    if (t) t.focus();
  } else {
    refreshTileFav(i);
    if (i === heroIndex) $('#heroStar').classList.toggle('on', isFav(ch));
  }
}

function setHero(i, withPreview = true) {
  const ch = S.list[i];
  if (!ch) return;
  if (i === heroIndex && withPreview && heroPreviewing) return;
  heroIndex = i;
  $('#heroGroup').textContent = ch.group;
  $('#heroName').textContent = ch.name;
  $('#heroMeta').textContent = `Channel ${i + 1} of ${S.list.length}`;
  $('#heroStar').classList.toggle('on', isFav(ch));
  setLogo($('#heroLogo'), $('#heroInitials'), ch);

  stopPreview();
  if (withPreview && S.preview) {
    heroPreviewing = true;
    previewTimer = setTimeout(() => previewEngine.play(ch), 900);
  }
}

function stopPreview() {
  heroPreviewing = false;
  clearTimeout(previewTimer);
  previewEngine.stop();
  previewVideo.classList.remove('on');
}

/* ----- Home events ----- */

groupsEl.addEventListener('click', (e) => {
  const b = e.target.closest('.ri');
  if (b) selectGroup(S.groups[+b.dataset.g]);
});

gridEl.addEventListener('click', (e) => {
  const t = e.target.closest('.tile');
  if (!t) return;
  const i = +t.dataset.i;
  if (e.target.closest('.fav')) { favFromGrid(i); return; }
  openPlayer(i);
});
gridEl.addEventListener('contextmenu', (e) => {
  const t = e.target.closest('.tile');
  if (!t) return;
  e.preventDefault();
  favFromGrid(+t.dataset.i);
});
gridEl.addEventListener('mouseover', (e) => {
  const t = e.target.closest('.tile');
  if (t) setHero(+t.dataset.i);
});
gridEl.addEventListener('focusin', (e) => {
  const t = e.target.closest('.tile');
  if (t) setHero(+t.dataset.i);
});

function gridCols() {
  return getComputedStyle(gridEl).gridTemplateColumns.split(' ').length || 1;
}

gridEl.addEventListener('keydown', (e) => {
  const t = e.target.closest('.tile');
  if (!t) return;
  const i = +t.dataset.i;
  const cols = gridCols();
  let n = null;
  switch (e.key) {
    case 'ArrowRight': n = i + 1; break;
    case 'ArrowLeft':
      if (i % cols === 0) { const a = groupsEl.querySelector('.active') || groupsEl.firstChild; if (a) a.focus(); e.preventDefault(); return; }
      n = i - 1; break;
    case 'ArrowDown': n = i + cols; break;
    case 'ArrowUp': n = i - cols; break;
    case 's': case 'S': favFromGrid(i); e.preventDefault(); return;
    default: return;
  }
  e.preventDefault();
  if (n >= 0 && n < S.list.length) {
    const nt = gridEl.children[n];
    nt.focus({ preventScroll: true });
    nt.scrollIntoView({ block: 'nearest' });
  }
});

groupsEl.addEventListener('keydown', (e) => {
  const b = e.target.closest('.ri');
  if (!b) return;
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    const n = e.key === 'ArrowDown' ? b.nextElementSibling : b.previousElementSibling;
    if (n) { n.focus(); n.click(); }
    e.preventDefault();
  } else if (e.key === 'ArrowRight') {
    const f = gridEl.firstElementChild;
    if (f) f.focus();
    e.preventDefault();
  }
});

let searchTimer = 0;
$('#search').addEventListener('input', (e) => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => { S.query = e.target.value.trim(); renderGrid(); }, 200);
});
$('#search').addEventListener('keydown', (e) => {
  if (e.key === 'Escape') { e.target.value = ''; S.query = ''; renderGrid(); e.target.blur(); }
  if (e.key === 'ArrowDown' || e.key === 'Enter') { const f = gridEl.firstElementChild; if (f) { f.focus(); e.preventDefault(); } }
});

$('#btnReload').addEventListener('click', load);
$('#btnSettings').addEventListener('click', openSettings);

/* ================= Player ================= */

const playerEl = $('#player');
const video = $('#video');
const P = { list: [], index: 0, open: false, idleTimer: 0, zapTimer: 0, numBuf: '', numTimer: 0 };

const engine = new Engine(video, {
  onPlaying: () => { $('#pSpinner').classList.add('hidden'); $('#pError').classList.add('hidden'); },
  onError: () => {
    $('#pSpinner').classList.add('hidden');
    $('#pError').classList.remove('hidden');
    wake(true);
  },
});
video.addEventListener('waiting', () => { if (P.open) $('#pSpinner').classList.remove('hidden'); });

function applyVolume() {
  video.volume = S.volume;
  video.muted = S.muted;
  $('#vol').value = S.muted ? 0 : S.volume;
  $('#btnMute use').setAttribute('href', S.muted || S.volume === 0 ? '#i-mute' : '#i-vol');
}

function openPlayer(i) {
  stopPreview();
  P.list = S.list.slice();
  P.index = i;
  P.open = true;
  playerEl.classList.remove('hidden');
  $('#pPanel').classList.add('hidden');
  applyVolume();
  showInfo();
  startCurrent();
  wake();
}

function closePlayer() {
  if (!P.open) return;
  P.open = false;
  clearTimeout(P.zapTimer);
  clearTimeout(P.idleTimer);
  engine.stop();
  playerEl.classList.add('hidden');
  playerEl.classList.remove('idle');
  if (window.api) window.api.setFullscreen(false);
  const ch = P.list[P.index];
  renderGrid(ch && ch.key);
}

function startCurrent() {
  const ch = P.list[P.index];
  if (!ch) return;
  $('#pError').classList.add('hidden');
  $('#pSpinner').classList.remove('hidden');
  engine.play(ch);
}

function zap(delta) {
  if (P.list.length < 2) return;
  P.index = (P.index + delta + P.list.length) % P.list.length;
  engine.stop();
  $('#pError').classList.add('hidden');
  $('#pSpinner').classList.remove('hidden');
  showInfo();
  wake();
  clearTimeout(P.zapTimer);
  P.zapTimer = setTimeout(startCurrent, 350);
}

function jumpTo(i) {
  if (i < 0 || i >= P.list.length) return;
  P.index = i;
  showInfo();
  startCurrent();
  wake();
}

function showInfo() {
  const ch = P.list[P.index];
  if (!ch) return;
  $('#pNum').textContent = `CH ${P.index + 1}`;
  $('#pGroup').textContent = ch.group;
  $('#pName').textContent = ch.name;
  $('#pStar').classList.toggle('on', isFav(ch));
  $('#btnFav').classList.toggle('on', isFav(ch));
  $('#btnFav use').setAttribute('href', isFav(ch) ? '#i-star' : '#i-star-o');
  if (P.list.length > 1) {
    const prev = P.list[(P.index - 1 + P.list.length) % P.list.length].name;
    const next = P.list[(P.index + 1) % P.list.length].name;
    $('#pNext').textContent = `▲  ${prev}        ▼  ${next}`;
  } else {
    $('#pNext').textContent = '';
  }
  setLogo($('#pLogo'), $('#pInitials'), ch);
  document.title = `${ch.name} · Live TV`;
  if (!$('#pPanel').classList.contains('hidden')) markPanel();
}

function updateClock() {
  const d = new Date();
  $('#pClock').textContent = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}
setInterval(updateClock, 10000);
updateClock();

/** Shows the overlay; hides it again after a few seconds without mouse/keys. */
function wake(stay = false) {
  playerEl.classList.remove('idle');
  clearTimeout(P.idleTimer);
  if (stay) return;
  P.idleTimer = setTimeout(() => {
    const busy = !$('#pPanel').classList.contains('hidden') ||
      !$('#pError').classList.contains('hidden') ||
      playerEl.querySelector('.p-bottom:hover, .p-top:hover');
    if (!busy && P.open) playerEl.classList.add('idle');
  }, 3500);
}

function favInPlayer() {
  const ch = P.list[P.index];
  if (!ch) return;
  toggleFav(ch);
  showInfo();
  wake();
}

/* ----- Channel side panel ----- */

function togglePanel(force) {
  const panel = $('#pPanel');
  const show = force !== undefined ? force : panel.classList.contains('hidden');
  panel.classList.toggle('hidden', !show);
  if (show) {
    const list = $('#pPanelList');
    list.innerHTML = '';
    const frag = document.createDocumentFragment();
    P.list.forEach((ch, i) => {
      const b = document.createElement('button');
      b.className = 'pl-item';
      b.dataset.i = i;
      b.innerHTML = `<span class="pl-num">${i + 1}</span><span class="pl-logo"><span class="ini"></span><img loading="lazy" alt=""></span><span class="pl-name"></span>`;
      b.querySelector('.pl-name').textContent = ch.name;
      setLogo(b.querySelector('img'), b.querySelector('.ini'), ch);
      frag.appendChild(b);
    });
    list.appendChild(frag);
    markPanel(true);
    wake(true);
  } else {
    wake();
  }
}

function markPanel(scroll) {
  const list = $('#pPanelList');
  for (const b of list.children) b.classList.toggle('cur', +b.dataset.i === P.index);
  const cur = list.children[P.index];
  if (cur && scroll !== false) cur.scrollIntoView({ block: 'center' });
}

$('#pPanelList').addEventListener('click', (e) => {
  const b = e.target.closest('.pl-item');
  if (b) jumpTo(+b.dataset.i);
});

/* ----- Player events ----- */

playerEl.addEventListener('mousemove', () => wake());
video.addEventListener('dblclick', () => window.api && window.api.toggleFullscreen());
video.addEventListener('click', () => { if (!$('#pPanel').classList.contains('hidden')) togglePanel(false); else wake(); });
$('#btnBack').addEventListener('click', closePlayer);
$('#btnPrev').addEventListener('click', () => zap(-1));
$('#btnNext').addEventListener('click', () => zap(1));
$('#btnFav').addEventListener('click', favInPlayer);
$('#btnList').addEventListener('click', () => togglePanel());
$('#btnPanelClose').addEventListener('click', () => togglePanel(false));
$('#btnFull').addEventListener('click', () => window.api && window.api.toggleFullscreen());
$('#btnRetry').addEventListener('click', startCurrent);
$('#btnNextCh').addEventListener('click', () => zap(1));
$('#btnMute').addEventListener('click', () => {
  S.muted = !S.muted;
  if (!S.muted && S.volume === 0) S.volume = 0.5;
  store.set('muted', S.muted); store.set('volume', S.volume);
  applyVolume();
});
$('#vol').addEventListener('input', (e) => {
  S.volume = +e.target.value;
  S.muted = S.volume === 0;
  store.set('volume', S.volume); store.set('muted', S.muted);
  applyVolume();
});
playerEl.addEventListener('wheel', (e) => {
  if (e.target.closest('.p-panel')) return;
  S.volume = Math.min(1, Math.max(0, Math.round((S.volume + (e.deltaY < 0 ? 0.05 : -0.05)) * 100) / 100));
  S.muted = S.volume === 0;
  store.set('volume', S.volume); store.set('muted', S.muted);
  applyVolume();
  wake();
}, { passive: true });

let isFull = false;
if (window.api) {
  window.api.onFullscreen((v) => {
    isFull = v;
    $('#btnFull use').setAttribute('href', v ? '#i-unfull' : '#i-full');
  });
}

function numberKey(d) {
  P.numBuf = (P.numBuf + d).slice(-4);
  const el = $('#pNumber');
  el.textContent = P.numBuf;
  el.classList.remove('hidden');
  clearTimeout(P.numTimer);
  P.numTimer = setTimeout(() => {
    const n = parseInt(P.numBuf, 10);
    P.numBuf = '';
    el.classList.add('hidden');
    if (n >= 1 && n <= P.list.length) jumpTo(n - 1);
    else toast(`No channel ${n}`);
  }, 1200);
}

/* ================= Settings ================= */

function openSettings() {
  $('#urlInput').value = S.url;
  $('#urlErr').classList.add('hidden');
  $('#previewToggle').checked = S.preview;
  $('#settings').classList.remove('hidden');
  stopPreview();
  setTimeout(() => $('#urlInput').focus(), 0);
}
function closeSettings() { $('#settings').classList.add('hidden'); }

function saveUrl(raw) {
  const url = raw.trim();
  if (!/^https?:\/\//i.test(url)) { $('#urlErr').classList.remove('hidden'); return; }
  const changed = url !== S.url;
  S.url = url;
  store.set('playlistUrl', url);
  closeSettings();
  if (changed) { S.group = null; store.set('lastGroup', null); }
  load();
}

$('#btnSave').addEventListener('click', () => saveUrl($('#urlInput').value));
$('#btnDefault').addEventListener('click', () => saveUrl(DEFAULT_PLAYLIST));
$('#urlInput').addEventListener('input', () => $('#urlErr').classList.add('hidden'));
$('#urlInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') saveUrl(e.target.value); });
$('#previewToggle').addEventListener('change', (e) => { S.preview = e.target.checked; store.set('livePreview', S.preview); });
$('#btnSettingsClose').addEventListener('click', closeSettings);
$('#settings').addEventListener('mousedown', (e) => { if (e.target.id === 'settings') closeSettings(); });

/* ================= Global keys ================= */

document.addEventListener('keydown', (e) => {
  const typing = e.target.matches('input[type=url], input[type=search]');

  if (!$('#settings').classList.contains('hidden')) {
    if (e.key === 'Escape') closeSettings();
    return;
  }

  if (P.open) {
    if (typing) return;
    switch (e.key) {
      case 'ArrowUp': case 'PageUp': zap(-1); break;
      case 'ArrowDown': case 'PageDown': zap(1); break;
      case 'Escape': case 'Backspace': case 'BrowserBack':
        if (!$('#pPanel').classList.contains('hidden')) togglePanel(false);
        else if (isFull && e.key === 'Escape') window.api.setFullscreen(false);
        else closePlayer();
        break;
      case 'f': case 'F': case 'F11': if (window.api) window.api.toggleFullscreen(); break;
      case 'm': case 'M': $('#btnMute').click(); break;
      case 's': case 'S': favInPlayer(); break;
      case 'l': case 'L': togglePanel(); break;
      case 'Enter':
        if (!$('#pError').classList.contains('hidden')) startCurrent(); else wake();
        break;
      case ' ':
        if (video.paused) video.play().catch(() => {}); else video.pause();
        break;
      case 'ArrowLeft': case 'ArrowRight': case 'i': case 'I': wake(); break;
      default:
        if (/^[0-9]$/.test(e.key)) numberKey(e.key);
        else return;
    }
    e.preventDefault();
    return;
  }

  // Home
  if ((e.ctrlKey && (e.key === 'f' || e.key === 'F')) || (e.key === '/' && !typing)) {
    $('#search').focus();
    $('#search').select();
    e.preventDefault();
  } else if (e.key === 'F5' || (e.ctrlKey && (e.key === 'r' || e.key === 'R'))) {
    load();
    e.preventDefault();
  } else if (e.key === 'F11') {
    if (window.api) window.api.toggleFullscreen();
    e.preventDefault();
  } else if (!typing && !document.activeElement.closest('.tile, .ri') &&
    ['ArrowDown', 'ArrowRight'].includes(e.key)) {
    const f = gridEl.firstElementChild;
    if (f) { f.focus(); e.preventDefault(); }
  }
});

/* ================= Start ================= */

if (window.api) {
  window.api.version().then((v) => { $('#version').textContent = `Live TV for Windows ${v}`; }).catch(() => {});
}
applyVolume();
load();
