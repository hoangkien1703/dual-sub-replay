package com.kienhoang.dualsubreplay.ui

internal const val PAGE_TOKEN_STATE_KEY = "__dualSubPageTokenV1"

/** The page asks for the track with a token at most this many times, one new request every [PAGE_TOKEN_RETRY_MS]. */
internal const val PAGE_TOKEN_TRIES = 5
internal const val PAGE_TOKEN_RETRY_MS = 4_000

/** A token minted for a video is used again for this long. */
internal const val PAGE_TOKEN_LIFE_MS = 300_000

/**
 * YouTube's own public web values, as LuanRT's BgUtils (MIT) documents them: the request key of
 * YouTube's BotGuard challenge and the API key its web player sends with `GenerateIT`.
 */
private const val BOTGUARD_REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
private const val BOTGUARD_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"

/**
 * Settings → Study tools → Load captions with a page token (beta). Installs
 * `window.__dualSubPageTokenV1.mint(videoId)` on a YouTube page: the page's own proof-of-origin
 * token for one video, made the way YouTube's web player makes it (the flow BgUtils documents).
 * The page asks its own `att/get` for a BotGuard challenge, loads BotGuard's interpreter (only from
 * `https://www.google.com/js/th/`), runs the challenge, trades the answer for an integrity token at
 * its own `/api/jnn/v1/GenerateIT`, and mints with the video id. The token stays in the page: only
 * `webPageTranscriptScript` uses it, for the track's address, and the app never sees it. A failure
 * is kept as a short code in `problem`.
 */
internal val WEB_PAGE_TOKEN_SCRIPT: String =
    """
    (function() {
      const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
      if (window.location.protocol !== 'https:' ||
          !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
      const key = '$PAGE_TOKEN_STATE_KEY';
      if (window[key]) return true;
      const state = window[key] = { tokens: {}, minter: null, minterUntil: 0, starting: null, problem: null, policy: null };
      const code = function(error) {
        return String((error && error.message) || 'failed').toLowerCase().replace(/[^a-z0-9-]/g, '-').slice(0, 40);
      };
      const config = function(name) {
        try {
          if (window.ytcfg && typeof window.ytcfg.get === 'function') return window.ytcfg.get(name);
        } catch (_) {}
        return undefined;
      };
      const fromBase64 = function(text) {
        const plain = String(text).replace(/-/g, '+').replace(/_/g, '/').replace(/\./g, '=').replace(/=+$/, '');
        const binary = atob(plain + '==='.slice(0, (4 - plain.length % 4) % 4));
        const bytes = new Uint8Array(binary.length);
        for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
        return bytes;
      };
      const toWebSafe = function(bytes) {
        let binary = '';
        for (let index = 0; index < bytes.length; index++) binary += String.fromCharCode(bytes[index]);
        return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_');
      };
      const allowedInterpreter = function(value) {
        try {
          const url = new URL(String(value), 'https://www.google.com/');
          return url.protocol === 'https:' && url.hostname === 'www.google.com' && url.pathname.indexOf('/js/th/') === 0 ? url : null;
        } catch (_) {
          return null;
        }
      };
      const challenge = function() {
        const context = config('INNERTUBE_CONTEXT');
        if (!context) return Promise.reject(new Error('no-context'));
        const headers = { 'content-type': 'application/json' };
        const visitor = config('VISITOR_DATA');
        if (visitor) headers['x-goog-visitor-id'] = String(visitor);
        const client = config('INNERTUBE_CONTEXT_CLIENT_NAME');
        if (client) headers['x-youtube-client-name'] = String(client);
        const version = config('INNERTUBE_CONTEXT_CLIENT_VERSION');
        if (version) headers['x-youtube-client-version'] = String(version);
        return fetch('/youtubei/v1/att/get?prettyPrint=false', {
          method: 'POST',
          credentials: 'include',
          headers: headers,
          body: JSON.stringify({ context: context, engagementType: 'ENGAGEMENT_TYPE_UNBOUND' })
        }).then(function(response) {
          if (!response.ok) throw new Error('challenge-' + response.status);
          return response.json();
        }).then(function(body) {
          const found = body && body.bgChallenge;
          if (!found || typeof found.program !== 'string' || typeof found.globalName !== 'string') throw new Error('no-challenge');
          return found;
        });
      };
      const interpreter = function(found) {
        const ready = window[found.globalName];
        if (ready && typeof ready.a === 'function') return Promise.resolve(ready);
        const wrapped = found.interpreterUrl && found.interpreterUrl.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue;
        const url = allowedInterpreter(wrapped);
        if (!url) return Promise.reject(new Error('interpreter-address'));
        return new Promise(function(resolve, reject) {
          const script = document.createElement('script');
          const nonced = document.querySelector('script[nonce]');
          if (nonced && nonced.nonce) script.nonce = nonced.nonce;
          let source = url.toString();
          try {
            if (window.trustedTypes && typeof window.trustedTypes.createPolicy === 'function') {
              state.policy = state.policy || window.trustedTypes.createPolicy('dualsub-page-token', {
                createScriptURL: function(value) {
                  if (!allowedInterpreter(value)) throw new Error('interpreter-address');
                  return value;
                }
              });
              source = state.policy.createScriptURL(url.toString());
            }
          } catch (_) {}
          script.onload = function() {
            const vm = window[found.globalName];
            if (vm && typeof vm.a === 'function') resolve(vm); else reject(new Error('no-vm'));
          };
          script.onerror = function() { reject(new Error('interpreter-blocked')); };
          try {
            script.src = source;
          } catch (_) {
            reject(new Error('interpreter-blocked'));
            return;
          }
          (document.head || document.documentElement).appendChild(script);
        });
      };
      const snapshot = function(vm, found) {
        return new Promise(function(resolve, reject) {
          const output = [];
          let settled = false;
          const settle = function(error, answer) {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            if (error) reject(error); else resolve({ answer: answer, output: output });
          };
          const timer = setTimeout(function() { settle(new Error('vm-timeout')); }, 10000);
          const setup = function(asyncSnapshot) {
            try {
              asyncSnapshot(function(answer) {
                if (typeof answer === 'string' && answer) settle(null, answer); else settle(new Error('no-snapshot'));
              }, [undefined, undefined, output, undefined]);
            } catch (_) {
              settle(new Error('no-snapshot'));
            }
          };
          try {
            vm.a(found.program, setup, true, undefined, function() {}, [[], []]);
          } catch (_) {
            settle(new Error('vm-failed'));
          }
        });
      };
      const integrity = function(answer) {
        return fetch('/api/jnn/v1/GenerateIT', {
          method: 'POST',
          headers: {
            'content-type': 'application/json+protobuf',
            'x-goog-api-key': '$BOTGUARD_API_KEY',
            'x-user-agent': 'grpc-web-javascript/0.1'
          },
          body: JSON.stringify(['$BOTGUARD_REQUEST_KEY', answer])
        }).then(function(response) {
          if (!response.ok) throw new Error('refused-' + response.status);
          return response.json();
        }).then(function(body) {
          if (!Array.isArray(body) || typeof body[0] !== 'string' || !body[0]) throw new Error('no-integrity-token');
          return { token: body[0], ttl: Number(body[1]) || 0 };
        });
      };
      const minter = function() {
        if (state.minter && Date.now() < state.minterUntil) return Promise.resolve(state.minter);
        if (state.starting) return state.starting;
        state.starting = challenge().then(function(found) {
          return interpreter(found).then(function(vm) { return snapshot(vm, found); });
        }).then(function(result) {
          return integrity(result.answer).then(function(granted) {
            const make = result.output[0];
            if (typeof make !== 'function') throw new Error('no-minter');
            return Promise.resolve(make(fromBase64(granted.token))).then(function(mint) {
              if (typeof mint !== 'function') throw new Error('no-minter');
              state.minter = mint;
              state.minterUntil = Date.now() + (granted.ttl > 0 ? granted.ttl * 900 : 1800000);
              return mint;
            });
          });
        });
        state.starting.then(function() {
          state.starting = null;
          state.problem = null;
        }, function(error) {
          state.starting = null;
          state.problem = code(error);
        });
        return state.starting;
      };
      state.mint = function(videoId) {
        if (typeof videoId !== 'string' || !/^[A-Za-z0-9_-]{11}$/.test(videoId)) return Promise.reject(new Error('bad-video'));
        const kept = state.tokens[videoId];
        if (kept && Date.now() - kept.at < $PAGE_TOKEN_LIFE_MS) return Promise.resolve(kept.token);
        return minter().then(function(mint) {
          return Promise.resolve(mint(new TextEncoder().encode(videoId)));
        }).then(function(bytes) {
          if (!(bytes instanceof Uint8Array) || !bytes.length) throw new Error('mint-failed');
          const token = toWebSafe(bytes);
          state.tokens[videoId] = { token: token, at: Date.now() };
          return token;
        });
      };
      return true;
    })();
    """.trimIndent()
