// Stage 1: a live clock, so you can see the app shell runs. Alarms arrive in stage 2.

const clockEl = document.getElementById('clock');
const dateEl = document.getElementById('date');
const buildEl = document.getElementById('build');

const timeFmt = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false });
const dateFmt = new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });

function tick() {
  const now = new Date();
  clockEl.textContent = timeFmt.format(now);
  dateEl.textContent = dateFmt.format(now).replace(',', '');
  // Re-run just after the next minute starts, so the clock never lags.
  setTimeout(tick, 60000 - (now.getSeconds() * 1000 + now.getMilliseconds()) + 50);
}

tick();
document.addEventListener('visibilitychange', () => { if (!document.hidden) clockEl.textContent = timeFmt.format(new Date()); });

buildEl.textContent = 'Build ' + (window.BUILD || 'local');
