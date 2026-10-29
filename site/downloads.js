// Shows the live total of GitHub release downloads in [data-download-count] elements.
(function () {
  var targets = document.querySelectorAll('[data-download-count]');
  if (!targets.length || !window.fetch) return;

  var CACHE_KEY = 'dualsub-download-count';
  var CACHE_MS = 60 * 60 * 1000;
  var API = 'https://api.github.com/repos/hoangkien1703/dual-sub-replay/releases?per_page=100&page=';

  function show(total) {
    var formatted = total.toLocaleString(document.documentElement.lang || 'en');
    targets.forEach(function (el) {
      el.textContent = formatted + (el.getAttribute('data-suffix') || '');
      el.hidden = false;
    });
  }

  try {
    var cached = JSON.parse(localStorage.getItem(CACHE_KEY) || 'null');
    if (cached && Date.now() - cached.at < CACHE_MS) return show(cached.total);
  } catch (e) {}

  function count(page, total) {
    return fetch(API + page)
      .then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
      .then(function (releases) {
        releases.forEach(function (release) {
          release.assets.forEach(function (asset) { total += asset.download_count; });
        });
        return releases.length === 100 && page < 10 ? count(page + 1, total) : total;
      });
  }

  count(1, 0).then(function (total) {
    if (!total) return;
    show(total);
    try { localStorage.setItem(CACHE_KEY, JSON.stringify({ total: total, at: Date.now() })); } catch (e) {}
  }).catch(function () {});
})();

// When the GitHub app is installed, Chrome hands the github.com APK link to it and the app's
// in-app browser never finishes the download. Once a visitor has tapped a download link and
// comes back to the page, show the [data-download-backup] links to the site's own APK copy.
(function () {
  var backups = document.querySelectorAll('[data-download-backup]');
  if (!backups.length) return;

  var STATE_KEY = 'dualsub-download-tapped';
  var tapped = false;

  function reveal() {
    backups.forEach(function (el) { el.hidden = false; });
  }

  try {
    if (sessionStorage.getItem(STATE_KEY)) reveal();
  } catch (e) {}

  document.addEventListener('click', function (event) {
    var link = event.target.closest && event.target.closest('a[href$="/releases/latest/download/DualSub-Replay.apk"]');
    if (!link) return;
    tapped = true;
    try { sessionStorage.setItem(STATE_KEY, '1'); } catch (e) {}
  });

  function onReturn() {
    if (tapped && document.visibilityState === 'visible') reveal();
  }
  document.addEventListener('visibilitychange', onReturn);
  window.addEventListener('pageshow', onReturn);
  window.addEventListener('focus', onReturn);
})();
