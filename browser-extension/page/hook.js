// Runs in the page's own world: copies each caption track YouTube's player downloads (the player's
// request already carries the cookies and proof-of-origin token YouTube demands). Never changes a request.
(() => {
  const trackUrl = (value) => {
    try {
      const url = new URL(String(value), location.href);
      const ok = url.protocol === 'https:' && /(^|\.)youtube\.com$/.test(url.hostname) &&
        url.pathname === '/api/timedtext' && url.searchParams.get('v') && !url.searchParams.get('tlang');
      return ok ? url : null;
    } catch {
      return null;
    }
  };

  const send = (url, text) => {
    if (typeof text !== 'string' || !text.trim() || text.length > 8 * 1024 * 1024) return;
    window.postMessage({ dualsub: 'track', videoId: url.searchParams.get('v'), lang: url.searchParams.get('lang') || '', text }, location.origin);
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
    }
    return open.apply(this, arguments);
  };

  const pageFetch = window.fetch;
  window.fetch = function (input) {
    const reply = pageFetch.apply(this, arguments);
    const url = trackUrl(input instanceof Request ? input.url : input);
    if (url) reply.then((r) => r.ok && r.clone().text().then((t) => send(url, t)), () => {});
    return reply;
  };
})();
