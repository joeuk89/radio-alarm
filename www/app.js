// Stage 2: one alarm (BBC Radio 4), set here and run entirely by native Android code.
// This file only draws the screen and passes changes to the native "Alarm" plugin.

const $ = (id) => document.getElementById(id);

const timeFmt = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false });
const secFmt = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
const dateFmt = new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
const dayFmt = new Intl.DateTimeFormat('en-GB', { weekday: 'short' });

// ---- Talking to the native side -------------------------------------------------------------

const native = window.Capacitor && window.Capacitor.nativePromise ? window.Capacitor : null;

// When the page is opened in an ordinary browser there is no native side; pretend, so the
// layout can still be looked at.
const preview = {
  alarm: { hour: 7, minute: 0, enabled: false, stationName: 'BBC Radio 4', startVolume: 5, endVolume: 60, rampSeconds: 180, snoozeMinutes: 9 },
  nextTrigger: 0, snoozeUntil: 0, ringing: false,
  checks: { exactAlarms: true, notifications: true, fullScreen: true, battery: false },
};

function call(method, options = {}) {
  if (native) return native.nativePromise('Alarm', method, options);
  if (method === 'setAlarm') Object.assign(preview.alarm, options);
  if (method === 'testAlarm') return Promise.resolve({ scheduled: true, at: Date.now() + 60000 });
  return Promise.resolve(preview);
}

// ---- Clock ----------------------------------------------------------------------------------

let state = null;

function tick() {
  const now = new Date();
  $('clock').textContent = timeFmt.format(now);
  $('date').textContent = dateFmt.format(now).replace(',', '');
  renderNext();
  // Run again just after the next minute starts, so the clock never lags.
  setTimeout(tick, 60000 - (now.getSeconds() * 1000 + now.getMilliseconds()) + 50);
}

// ---- Drawing --------------------------------------------------------------------------------

const pad = (n) => String(n).padStart(2, '0');

function renderNext() {
  const el = $('next');
  const cancel = $('cancelSnooze');
  cancel.hidden = true;
  if (!state) return;
  const now = Date.now();

  if (state.ringing) {
    el.textContent = 'Alarm ringing';
  } else if (state.snoozeUntil > now) {
    el.textContent = 'Snoozed until ' + timeFmt.format(new Date(state.snoozeUntil));
    cancel.hidden = false;
  } else if (state.nextTrigger > now) {
    const mins = Math.max(1, Math.round((state.nextTrigger - now) / 60000));
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    const inText = h ? `${h} h ${m} min` : `${m} min`;
    const at = new Date(state.nextTrigger);
    el.textContent = `Next alarm in ${inText} · ${dayFmt.format(at)} ${timeFmt.format(at)}`;
  } else {
    el.textContent = 'Alarm off';
  }
}

function renderAlarm() {
  const a = state.alarm;
  if (document.activeElement !== $('time')) $('time').value = `${pad(a.hour)}:${pad(a.minute)}`;
  $('station').textContent = a.stationName;
  const ramp = a.rampSeconds % 60 === 0 ? `${a.rampSeconds / 60} min` : `${a.rampSeconds} s`;
  $('detail').textContent = `Every day · ${a.startVolume}% → ${a.endVolume}% over ${ramp}`;
  $('toggle').setAttribute('aria-checked', String(a.enabled));
  document.querySelector('.alarm-card').classList.toggle('off', !a.enabled);
}

const CHECKS = [
  { key: 'exactAlarms', title: 'Exact alarms', ok: 'Fires on the minute, even when idle', bad: 'Not allowed — the alarm cannot be set' },
  { key: 'notifications', title: 'Notifications', ok: 'Needed to keep the radio playing', bad: 'Off — the alarm screen cannot appear' },
  { key: 'fullScreen', title: 'Full-screen alarm', ok: 'Shows snooze and stop over the lock screen', bad: 'Off — no alarm screen when locked' },
  { key: 'battery', title: 'Battery: unrestricted', ok: 'Android will not stop the app overnight', bad: 'Currently optimised — may be stopped overnight' },
];

const TICK_SVG = '<svg class="mark" viewBox="0 0 24 24" fill="none" stroke="#E8E4DC" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="Done"><polyline points="5 12 10 17 19 7"></polyline></svg>';
const WARN_SVG = '<svg class="mark" viewBox="0 0 24 24" fill="none" stroke="#E8A33D" stroke-width="2" stroke-linecap="round" role="img" aria-label="Needs fixing"><circle cx="12" cy="12" r="9"></circle><line x1="12" y1="7.5" x2="12" y2="13"></line><line x1="12" y1="16.5" x2="12" y2="16.6"></line></svg>';

function renderChecks() {
  const box = $('checks');
  box.innerHTML = '';
  for (const c of CHECKS) {
    const ok = !!state.checks[c.key];
    const row = document.createElement('div');
    row.className = 'row' + (ok ? '' : ' bad');
    row.innerHTML = (ok ? TICK_SVG : WARN_SVG) +
      `<div class="text"><span class="title">${c.title}</span><span class="sub">${ok ? c.ok : c.bad}</span></div>`;
    if (!ok) {
      const fix = document.createElement('button');
      fix.className = 'fix';
      fix.textContent = 'Fix';
      fix.addEventListener('click', () => call('fix', { which: c.key }));
      row.appendChild(fix);
    }
    box.appendChild(row);
  }
}

function render(newState) {
  if (newState && newState.alarm) state = newState;
  if (!state) return;
  renderAlarm();
  renderChecks();
  renderNext();
}

const refresh = () => call('getState').then(render).catch(() => {});

// ---- Actions --------------------------------------------------------------------------------

function saveAlarm(changes) {
  const a = Object.assign({}, state.alarm, changes);
  return call('setAlarm', { hour: a.hour, minute: a.minute, enabled: a.enabled }).then((result) => {
    render(result);
    if (result.scheduled === false) {
      $('next').textContent = 'Could not set the alarm — see the reliability check';
    }
  });
}

$('time').addEventListener('change', (e) => {
  const [h, m] = e.target.value.split(':').map(Number);
  if (Number.isFinite(h) && Number.isFinite(m)) saveAlarm({ hour: h, minute: m });
});

$('toggle').addEventListener('click', () => saveAlarm({ enabled: !state.alarm.enabled }));

$('cancelSnooze').addEventListener('click', () => call('cancelSnooze').then(render));

function test(badStream) {
  call('testAlarm', { seconds: 60, badStream }).then((result) => {
    $('testNote').textContent = result.scheduled
      ? `${badStream ? 'Backup-tone test' : 'Test alarm'} set for ${secFmt.format(new Date(result.at))}. Lock the phone now.`
      : 'Could not set the test — see the reliability check.';
  });
}
$('testRadio').addEventListener('click', () => test(false));
$('testBackup').addEventListener('click', () => test(true));

// ---- Start ----------------------------------------------------------------------------------

$('build').textContent = 'Build ' + (window.BUILD || 'local');
tick();
refresh();
// Coming back from a settings screen or the alarm screen: re-read everything.
document.addEventListener('visibilitychange', () => { if (!document.hidden) { refresh(); $('clock').textContent = timeFmt.format(new Date()); } });
window.addEventListener('focus', refresh);
setInterval(refresh, 15000);
