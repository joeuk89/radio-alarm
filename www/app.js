// The web UI. It only draws screens and passes changes to the native "Alarm" plugin;
// scheduling, playback and the lock-screen alarm are all native Android code.

const $ = (id) => document.getElementById(id);

const timeFmt = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false });
const secFmt = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
const dateFmt = new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
const dayFmt = new Intl.DateTimeFormat('en-GB', { weekday: 'short' });

const DAY_LETTERS = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
const DAY_SHORT = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const DAY_FULL = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

const pad = (n) => String(n).padStart(2, '0');
const clone = (o) => JSON.parse(JSON.stringify(o));

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

// ---- Talking to the native side -------------------------------------------------------------

const native = window.Capacitor && window.Capacitor.nativePromise ? window.Capacitor : null;

// Opened in an ordinary browser there is no native side; pretend, so the layout can be looked at.
const mock = {
  alarms: [
    { id: 1, hour: 7, minute: 0, enabled: true, days: [true, true, true, true, true, false, false], stationName: 'BBC Radio 4', stationUrl: 'radio4', stationHls: true, startVolume: 5, endVolume: 60, rampSeconds: 180, snoozeMinutes: 9, next: Date.now() + 8 * 3600000 },
  ],
  favourites: [
    { name: 'BBC Radio 4', url: 'radio4', hls: true, detail: 'United Kingdom · Talk, news' },
    { name: 'BBC World Service', url: 'ws', hls: true, detail: 'United Kingdom · News, talk' },
  ],
  nextTrigger: Date.now() + 8 * 3600000, snoozeUntil: 0, ringing: false,
  checks: { exactAlarms: true, notifications: true, fullScreen: true, battery: false },
  preview: { status: 'idle', url: '' },
};

function mockCall(method, o) {
  if (method === 'saveAlarm') {
    const a = clone(o.alarm);
    if (!a.id) { a.id = Math.max(0, ...mock.alarms.map((x) => x.id)) + 1; mock.alarms.push(a); }
    else mock.alarms = mock.alarms.map((x) => (x.id === a.id ? a : x));
  }
  if (method === 'setEnabled') mock.alarms.forEach((x) => { if (x.id === o.id) x.enabled = o.enabled; });
  if (method === 'deleteAlarm') mock.alarms = mock.alarms.filter((x) => x.id !== o.id);
  if (method === 'setFavourites') mock.favourites = clone(o.favourites);
  if (method === 'testAlarm') return { scheduled: true, at: Date.now() + o.seconds * 1000 };
  if (method === 'previewStart') mock.preview = { status: 'playing', url: o.url };
  if (method === 'previewStop') mock.preview = { status: 'idle', url: '' };
  if (method.startsWith('preview')) return mock.preview;
  return clone(mock);
}

function call(method, options = {}) {
  if (native) return native.nativePromise('Alarm', method, options);
  return Promise.resolve(mockCall(method, options));
}

// ---- State ----------------------------------------------------------------------------------

let state = null;      // everything the native side knows
let draft = null;      // the alarm being edited
let view = 'home';
let preview = { status: 'idle', url: '' };
let previewTimer = null;
let results = null;    // station search results, or null before any search
let searchSeq = 0;
let searchTimer = null;

function newAlarm() {
  const fav = (state && state.favourites && state.favourites[0]) || { name: 'BBC Radio 4', url: '', hls: true };
  return {
    id: 0, hour: 7, minute: 0, enabled: true,
    days: [true, true, true, true, true, false, false],
    stationName: fav.name, stationUrl: fav.url, stationHls: !!fav.hls,
    startVolume: 5, endVolume: 60, rampSeconds: 180, snoozeMinutes: 9,
  };
}

function daysSummary(days) {
  const on = days.map((d, i) => (d ? i : -1)).filter((i) => i >= 0);
  if (on.length === 0) return 'Once';
  if (on.length === 7) return 'Every day';
  const first = on[0];
  const last = on[on.length - 1];
  if (on.length >= 3 && last - first + 1 === on.length) return `${DAY_SHORT[first]}–${DAY_SHORT[last]}`;
  return on.map((i) => DAY_SHORT[i]).join(', ');
}

function rampSummary(a) {
  if (a.rampSeconds <= 0 || a.startVolume >= a.endVolume) return `${a.endVolume}%`;
  return `${a.startVolume}% → ${a.endVolume}% over ${Math.round(a.rampSeconds / 60)} min`;
}

// ---- Navigation -----------------------------------------------------------------------------
// Each screen is a history entry, so the phone's back gesture works.

const VIEWS = ['home', 'edit', 'stations', 'setup'];

function go(name) { location.hash = name; }
function back() { history.back(); }

function route() {
  let name = location.hash.replace('#', '') || 'home';
  if (!VIEWS.includes(name)) name = 'home';
  if ((name === 'edit' || name === 'stations') && !draft) {
    history.replaceState(null, '', location.pathname);
    name = 'home';
  }
  if (view === 'stations' && name !== 'stations') stopPreview();
  // Leaving the editor ends any alarm preview that is still playing.
  if (view === 'edit' && name === 'home') call('stopAlarmPreview');
  view = name;
  for (const v of VIEWS) $('view-' + v).hidden = v !== name;
  window.scrollTo(0, 0);
  render();
}

// ---- Home -----------------------------------------------------------------------------------

function tick() {
  const now = new Date();
  $('clock').textContent = timeFmt.format(now);
  $('date').textContent = dateFmt.format(now).replace(',', '');
  renderNext();
  // Run again just after the next minute starts, so the clock never lags.
  setTimeout(tick, 60000 - (now.getSeconds() * 1000 + now.getMilliseconds()) + 50);
}

function renderNext() {
  const line = $('next');
  const action = $('nextAction');
  action.hidden = true;
  if (!state) return;
  const now = Date.now();

  if (scheduleFailed) {
    line.textContent = 'Could not set the alarm — open the reliability check';
  } else if (state.ringing) {
    line.textContent = 'Alarm ringing';
    action.textContent = 'Stop';
    action.onclick = () => call('stopRinging').then(() => setTimeout(refresh, 600));
    action.hidden = false;
  } else if (state.snoozeUntil > now) {
    line.textContent = 'Snoozed until ' + timeFmt.format(new Date(state.snoozeUntil));
    action.textContent = 'Cancel snooze';
    action.onclick = () => call('cancelSnooze').then(render);
    action.hidden = false;
  } else if (state.nextTrigger > now) {
    const mins = Math.max(1, Math.round((state.nextTrigger - now) / 60000));
    const d = Math.floor(mins / 1440);
    const h = Math.floor((mins % 1440) / 60);
    const m = mins % 60;
    const inText = d ? `${d} d ${h} h` : h ? `${h} h ${m} min` : `${m} min`;
    const at = new Date(state.nextTrigger);
    line.textContent = `Next alarm in ${inText} · ${dayFmt.format(at)} ${timeFmt.format(at)}`;
  } else {
    line.textContent = state.alarms.length ? 'No alarms on' : 'No alarms yet';
  }
}

function renderHome() {
  const box = $('alarms');
  box.replaceChildren();
  const alarms = state.alarms.slice().sort((a, b) => a.hour * 60 + a.minute - (b.hour * 60 + b.minute));

  if (!alarms.length) {
    box.appendChild(el('p', 'card empty muted', 'Add an alarm to wake up to the radio.'));
  }
  for (const a of alarms) {
    const card = el('div', 'card alarm-card' + (a.enabled ? '' : ' off'));

    const open = el('button', 'alarm-open');
    open.appendChild(el('div', 'alarm-time', `${pad(a.hour)}:${pad(a.minute)}`));
    open.appendChild(el('div', 'alarm-station ellipsis', a.stationName));
    open.appendChild(el('div', 'muted small', `${daysSummary(a.days)} · ${a.enabled ? rampSummary(a) : 'off'}`));
    open.addEventListener('click', () => { draft = clone(a); go('edit'); });

    const toggle = el('button', 'toggle');
    toggle.setAttribute('role', 'switch');
    toggle.setAttribute('aria-checked', String(a.enabled));
    toggle.setAttribute('aria-label', `Alarm at ${pad(a.hour)}:${pad(a.minute)}`);
    const track = el('span', 'track');
    track.appendChild(el('span', 'thumb'));
    toggle.appendChild(track);
    toggle.addEventListener('click', () => call('setEnabled', { id: a.id, enabled: !a.enabled }).then(afterSave));

    card.append(open, toggle);
    box.appendChild(card);
  }

  $('setupDot').hidden = Object.values(state.checks).every(Boolean);
  renderNext();
}

let scheduleFailed = false;

function afterSave(result) {
  if (result) scheduleFailed = result.scheduled === false;
  render(result);
}

// ---- Edit alarm -----------------------------------------------------------------------------

function renderEdit() {
  if (!draft) return;
  $('editTitle').textContent = draft.id ? 'Edit alarm' : 'New alarm';
  const time = `${pad(draft.hour)}:${pad(draft.minute)}`;
  $('editTimeText').textContent = time;
  if ($('editTime').value !== time) $('editTime').value = time;

  const days = $('editDays');
  days.replaceChildren();
  draft.days.forEach((on, i) => {
    const b = el('button', 'day', DAY_LETTERS[i]);
    b.setAttribute('aria-pressed', String(on));
    b.setAttribute('aria-label', DAY_FULL[i]);
    b.addEventListener('click', () => { draft.days[i] = !draft.days[i]; renderEdit(); });
    days.appendChild(b);
  });
  $('editDaysNote').textContent = draft.days.some(Boolean)
    ? daysSummary(draft.days)
    : 'Rings once, then switches off';
  $('editAllDays').textContent = draft.days.every(Boolean) ? 'Clear days' : 'Every day';

  $('editStationName').textContent = draft.stationName;

  $('startVolume').value = draft.startVolume;
  $('endVolume').value = draft.endVolume;
  $('rampMinutes').value = Math.round(draft.rampSeconds / 60);
  $('snoozeMinutes').value = draft.snoozeMinutes;
  $('startVolumeValue').textContent = draft.startVolume + '%';
  $('endVolumeValue').textContent = draft.endVolume + '%';
  $('rampMinutesValue').textContent = draft.rampSeconds ? Math.round(draft.rampSeconds / 60) + ' min' : 'Off';
  $('snoozeMinutesValue').textContent = draft.snoozeMinutes + ' min';

  $('editDelete').hidden = !draft.id;
}

$('editTime').addEventListener('change', (e) => {
  const [h, m] = e.target.value.split(':').map(Number);
  if (Number.isFinite(h) && Number.isFinite(m)) { draft.hour = h; draft.minute = m; }
  renderEdit();
});

$('editAllDays').addEventListener('click', () => {
  const all = draft.days.every(Boolean);
  draft.days = draft.days.map(() => !all);
  renderEdit();
});

// Only one slider responds at a time: once a drag starts, the others ignore the finger
// until it lifts, so sliding off one slider cannot move its neighbour.
document.querySelectorAll('#view-edit input[type="range"]').forEach((slider) => {
  slider.addEventListener('pointerdown', (e) => {
    $('view-edit').classList.add('dragging');
    slider.classList.add('active');
    try { slider.setPointerCapture(e.pointerId); } catch (err) { /* not essential */ }
  });
});
function endSliderDrag() {
  $('view-edit').classList.remove('dragging');
  document.querySelectorAll('#view-edit input[type="range"].active').forEach((s) => s.classList.remove('active'));
}
['pointerup', 'pointercancel', 'touchend', 'touchcancel'].forEach((type) => window.addEventListener(type, endSliderDrag));

$('startVolume').addEventListener('input', (e) => {
  draft.startVolume = Number(e.target.value);
  if (draft.endVolume < draft.startVolume) draft.endVolume = draft.startVolume;
  renderEdit();
});
$('endVolume').addEventListener('input', (e) => {
  draft.endVolume = Number(e.target.value);
  if (draft.startVolume > draft.endVolume) draft.startVolume = draft.endVolume;
  renderEdit();
});
$('rampMinutes').addEventListener('input', (e) => { draft.rampSeconds = Number(e.target.value) * 60; renderEdit(); });
$('snoozeMinutes').addEventListener('input', (e) => { draft.snoozeMinutes = Number(e.target.value); renderEdit(); });

$('editStation').addEventListener('click', () => go('stations'));
$('editCancel').addEventListener('click', back);

function saveDraft() {
  // Saving an alarm switches it on: that is nearly always what was meant.
  draft.enabled = true;
  call('saveAlarm', { alarm: draft }).then((result) => {
    draft = null;
    state = result;
    back();
    afterSave(result);
  });
}
$('editSave').addEventListener('click', saveDraft);
$('editSaveBottom').addEventListener('click', saveDraft);

$('editDelete').addEventListener('click', () => {
  askConfirm(`Delete the ${pad(draft.hour)}:${pad(draft.minute)} alarm?`).then((yes) => {
    if (!yes || !draft) return;
    call('deleteAlarm', { id: draft.id }).then((result) => {
      draft = null;
      state = result;
      back();
    });
  });
});

// Rings straight away with these settings, on the real alarm screen.
$('editPreview').addEventListener('click', () => call('previewAlarm', { alarm: draft }));

// ---- Confirmation dialog --------------------------------------------------------------------

let confirmResolve = null;
function askConfirm(text) {
  $('confirmText').textContent = text;
  $('confirm').hidden = false;
  return new Promise((resolve) => { confirmResolve = resolve; });
}
function closeConfirm(answer) {
  $('confirm').hidden = true;
  if (confirmResolve) confirmResolve(answer);
  confirmResolve = null;
}
$('confirmYes').addEventListener('click', () => closeConfirm(true));
$('confirmNo').addEventListener('click', () => closeConfirm(false));
$('confirm').addEventListener('click', (e) => { if (e.target === $('confirm')) closeConfirm(false); });

// ---- Station picker -------------------------------------------------------------------------

const ICON_PLAY = '<svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="7 4 20 12 7 20"></polygon></svg>';
const ICON_STOP = '<svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><rect x="5" y="5" width="14" height="14" rx="2"></rect></svg>';
const ICON_STAR_ON = '<svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><polygon points="12 3 14.8 9 21.3 9.6 16.4 14 17.9 20.5 12 17.1 6.1 20.5 7.6 14 2.7 9.6 9.2 9"></polygon></svg>';
const ICON_STAR_OFF = '<svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"><polygon points="12 3 14.8 9 21.3 9.6 16.4 14 17.9 20.5 12 17.1 6.1 20.5 7.6 14 2.7 9.6 9.2 9"></polygon></svg>';
const ICON_TICK = '<svg class="mark" viewBox="0 0 24 24" fill="none" stroke="#E8E4DC" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="Chosen"><polyline points="5 12 10 17 19 7"></polyline></svg>';
const ICON_WARN = '<svg class="mark" viewBox="0 0 24 24" fill="none" stroke="#E8A33D" stroke-width="2" stroke-linecap="round" role="img" aria-label="Needs fixing"><circle cx="12" cy="12" r="9"></circle><line x1="12" y1="7.5" x2="12" y2="13"></line><line x1="12" y1="16.5" x2="12" y2="16.6"></line></svg>';

const isFavourite = (url) => state.favourites.some((f) => f.url === url);

function stationRow(station) {
  const testing = preview.url === station.url && (preview.status === 'loading' || preview.status === 'playing');
  const failed = preview.url === station.url && preview.status === 'error';
  const row = el('div', 'row' + (testing ? ' testing' : '') + (failed ? ' failed' : ''));

  const play = el('button', 'round' + (testing ? ' on' : ''));
  play.innerHTML = testing ? ICON_STOP : ICON_PLAY;
  play.setAttribute('aria-label', (testing ? 'Stop test of ' : 'Test play ') + station.name);
  play.addEventListener('click', () => (testing ? stopPreview() : startPreview(station)));

  const pick = el('button', 'pick');
  const text = el('div', 'text');
  text.appendChild(el('span', 'title', station.name));
  let sub = station.detail || '';
  if (testing) sub = preview.status === 'playing' ? 'Playing test…' : 'Loading…';
  if (failed) sub = 'Could not play this station';
  text.appendChild(el('span', 'sub', sub));
  pick.appendChild(text);
  if (draft && draft.stationUrl === station.url) pick.insertAdjacentHTML('beforeend', ICON_TICK);
  pick.addEventListener('click', () => {
    draft.stationName = station.name;
    draft.stationUrl = station.url;
    draft.stationHls = !!station.hls;
    back();
  });

  const fav = isFavourite(station.url);
  const star = el('button', 'star' + (fav ? ' on' : ''));
  star.innerHTML = fav ? ICON_STAR_ON : ICON_STAR_OFF;
  star.setAttribute('aria-label', (fav ? 'Remove from favourites: ' : 'Add to favourites: ') + station.name);
  star.addEventListener('click', () => toggleFavourite(station));

  row.append(play, pick, star);
  return row;
}

function renderStations() {
  const favBox = $('favourites');
  favBox.replaceChildren();
  if (state.favourites.length) {
    state.favourites.forEach((s) => favBox.appendChild(stationRow(s)));
  } else {
    favBox.appendChild(el('p', 'empty muted', 'No favourites yet. Search below and tap a star.'));
  }

  const box = $('results');
  const has = Array.isArray(results) && results.length > 0;
  $('resultsTitle').hidden = !has;
  box.hidden = !has;
  box.replaceChildren();
  if (has) results.forEach((s) => box.appendChild(stationRow(s)));
}

function toggleFavourite(station) {
  const list = isFavourite(station.url)
    ? state.favourites.filter((f) => f.url !== station.url)
    : state.favourites.concat([{ name: station.name, url: station.url, hls: !!station.hls, detail: station.detail || '' }]);
  state.favourites = list;
  call('setFavourites', { favourites: list });
  renderStations();
}

// Test-play runs through the same native player the alarm uses, so "it played here" means
// "it will play as an alarm".
function startPreview(station) {
  preview = { status: 'loading', url: station.url };
  renderStations();
  call('previewStart', { url: station.url, hls: !!station.hls });
  clearInterval(previewTimer);
  previewTimer = setInterval(() => {
    call('previewState').then((p) => {
      if (p.url !== preview.url) return;
      if (p.status !== preview.status) { preview = p; renderStations(); }
      if (p.status === 'error' || p.status === 'idle') clearInterval(previewTimer);
    });
  }, 700);
}

function stopPreview() {
  clearInterval(previewTimer);
  if (preview.url) call('previewStop');
  preview = { status: 'idle', url: '' };
  if (view === 'stations') renderStations();
}

// Station search uses the community Radio Browser directory. It has several mirror servers;
// try them in turn and remember the one that answers.
let radioBrowserHosts = ['de2.api.radio-browser.info', 'de1.api.radio-browser.info', 'fi1.api.radio-browser.info', 'all.api.radio-browser.info'];

async function radioBrowser(path) {
  let lastError = new Error('No server answered');
  for (const host of radioBrowserHosts.slice()) {
    try {
      const response = await fetch(`https://${host}${path}`, { headers: { 'User-Agent': 'RadioAlarm/1.0' } });
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const data = await response.json();
      radioBrowserHosts = [host].concat(radioBrowserHosts.filter((h) => h !== host));
      return data;
    } catch (e) {
      lastError = e;
    }
  }
  throw lastError;
}

function toStation(s) {
  const url = (s.url_resolved || s.url || '').trim();
  const tags = (s.tags || '').split(',').map((t) => t.trim()).filter(Boolean).slice(0, 2).join(', ');
  const quality = [s.codec, s.bitrate ? s.bitrate + 'k' : ''].filter(Boolean).join(' ');
  return {
    name: (s.name || '').trim() || 'Unnamed station',
    url,
    hls: s.hls === 1 || /\.m3u8(\?|$)/i.test(url),
    detail: [s.country, tags, quality].filter(Boolean).join(' · '),
  };
}

function search(query) {
  const seq = ++searchSeq;
  const note = $('searchNote');
  if (query.length < 2) {
    results = null;
    note.hidden = true;
    renderStations();
    return;
  }
  note.textContent = 'Searching…';
  note.hidden = false;
  const path = `/json/stations/search?name=${encodeURIComponent(query)}&limit=40&hidebroken=true&order=clickcount&reverse=true`;
  radioBrowser(path).then((list) => {
    if (seq !== searchSeq) return;
    const seen = new Set();
    results = list.map(toStation).filter((s) => s.url && !seen.has(s.url) && seen.add(s.url));
    note.hidden = results.length > 0;
    note.textContent = 'No stations found.';
    renderStations();
  }).catch(() => {
    if (seq !== searchSeq) return;
    results = null;
    note.textContent = 'Search failed. Check the internet connection and try again.';
    note.hidden = false;
    renderStations();
  });
}

$('search').addEventListener('input', (e) => {
  clearTimeout(searchTimer);
  const query = e.target.value.trim();
  searchTimer = setTimeout(() => search(query), 450);
});
$('search').addEventListener('keydown', (e) => { if (e.key === 'Enter') e.target.blur(); });

// ---- Reliability check ----------------------------------------------------------------------

const CHECKS = [
  { key: 'exactAlarms', title: 'Exact alarms', ok: 'Fires on the minute, even when idle', bad: 'Not allowed — alarms cannot be set' },
  { key: 'notifications', title: 'Notifications', ok: 'Needed to keep the radio playing', bad: 'Off — the alarm screen cannot appear' },
  { key: 'fullScreen', title: 'Full-screen alarm', ok: 'Shows snooze and stop over the lock screen', bad: 'Off — no alarm screen when locked' },
  { key: 'battery', title: 'Battery: unrestricted', ok: 'Android will not stop the app overnight', bad: 'Currently optimised — may be stopped overnight' },
];

function renderSetup() {
  const box = $('checks');
  box.replaceChildren();
  let allOk = true;
  for (const c of CHECKS) {
    const ok = !!state.checks[c.key];
    allOk = allOk && ok;
    const row = el('div', 'row' + (ok ? '' : ' bad'));
    row.insertAdjacentHTML('beforeend', ok ? ICON_TICK.replace('Chosen', 'Done') : ICON_WARN);
    const text = el('div', 'text');
    text.appendChild(el('span', 'title', c.title));
    text.appendChild(el('span', 'sub', ok ? c.ok : c.bad));
    row.appendChild(text);
    if (!ok) {
      const fix = el('button', 'fix', 'Fix');
      fix.addEventListener('click', () => call('fix', { which: c.key }));
      row.appendChild(fix);
    }
    box.appendChild(row);
  }
  $('setupIntro').textContent = allOk
    ? 'Everything Android needs is switched on.'
    : 'Android needs these switched on so the alarm always goes off. Tap Fix on each one.';
  $('build').textContent = 'Build ' + (window.BUILD || 'local');
}

function test(badStream) {
  const alarm = (state.alarms[0] && clone(state.alarms[0])) || newAlarm();
  call('testAlarm', { alarm, seconds: 10, badStream }).then((result) => {
    $('testNote').textContent = result.scheduled
      ? `${badStream ? 'Backup-tone test' : 'Test alarm'} set for ${secFmt.format(new Date(result.at))}. Lock the phone now.`
      : 'Could not set the test — fix the items above first.';
  });
}
$('testRadio').addEventListener('click', () => test(false));
$('testBackup').addEventListener('click', () => test(true));

// ---- Shared ---------------------------------------------------------------------------------

function render(newState) {
  if (newState && newState.alarms) state = newState;
  if (!state) return;
  if (view === 'home') renderHome();
  if (view === 'edit') renderEdit();
  if (view === 'stations') renderStations();
  if (view === 'setup') renderSetup();
}

const refresh = () => call('getState').then((s) => {
  // Don't let a background refresh undo a favourite that is still being saved.
  if (state && view === 'stations') s.favourites = state.favourites;
  render(s);
}).catch(() => {});

$('addAlarm').addEventListener('click', () => { draft = newAlarm(); go('edit'); });
$('openSetup').addEventListener('click', () => go('setup'));
document.querySelectorAll('[data-back]').forEach((b) => b.addEventListener('click', back));

window.addEventListener('hashchange', () => { closeConfirm(false); route(); });
// Coming back from a settings screen or the alarm screen: re-read everything.
document.addEventListener('visibilitychange', () => { if (!document.hidden) { refresh(); $('clock').textContent = timeFmt.format(new Date()); } });
window.addEventListener('focus', refresh);
setInterval(refresh, 15000);

call('getState').then((s) => {
  state = s;
  route();
  tick();
});
