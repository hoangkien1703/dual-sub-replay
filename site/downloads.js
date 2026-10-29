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
