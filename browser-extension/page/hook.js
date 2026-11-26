// Runs in the page's own world: copies each caption track YouTube's player downloads (the player's
// request already carries the cookies and proof-of-origin token YouTube demands). Never changes a request.
(() => {
  // A caption track address on YouTube; [translated] true for one YouTube translates (it carries tlang).
  const trackUrl = (value, translated = false) => {
    try {
      const url = new URL(String(value), location.href);
      const ok = url.protocol === 'https:' && /(^|\.)youtube\.com$/.test(url.hostname) &&
        url.pathname === '/api/timedtext' && url.searchParams.get('v') && !url.searchParams.get('tlang') !== translated;
      return ok ? url : null;
    } catch {
      return null;
    }
  };

  const send = (url, text) => {
    if (typeof text !== 'string' || !text.trim() || text.length > 8 * 1024 * 1024) return;
    window.postMessage({ dualsub: 'track', videoId: url.searchParams.get('v'), lang: url.searchParams.get('lang') || '', text }, location.origin);
  };

  const pageFetch = window.fetch;

  // With YouTube's auto-translate on, the player downloads only the translated track. The original is the same
  // address without tlang (same host, cookies and token, nothing leaves YouTube); it is asked for once.
  const asked = new Set();
  const originalOf = (value) => {
    const url = trackUrl(value, true);
    if (!url) return;
    url.searchParams.delete('tlang');
    if (asked.has(url.href)) return;
    asked.add(url.href);
    pageFetch(url.href, { credentials: 'include' }).then((r) => r.ok && r.text().then((t) => send(url, t)), () => {});
  };

  const open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, address) {
    const url = trackUrl(address);
    if (url) {
      this.addEventListener('load', () => {
        if (this.status < 200 || this.status >= 300) return;
        if (this.responseType === '' || this.responseType === 'text') send(url, this.responseText);
        else if (this.responseType === 'json') send(url, JSON.stringify(this.response));
        else if (this.responseType === 'arraybuffer' && this.response) send(url, new TextDecoder().decode(this.response));
      });
    } else originalOf(address);
    return open.apply(this, arguments);
  };

  window.fetch = function (input) {
    const reply = pageFetch.apply(this, arguments);
    const address = input instanceof Request ? input.url : input;
    const url = trackUrl(address);
    if (url) reply.then((r) => r.ok && r.clone().text().then((t) => send(url, t)), () => {});
    else originalOf(address);
    return reply;
  };
})();
