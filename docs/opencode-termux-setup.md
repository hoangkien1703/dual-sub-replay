# Set up "OpenCode on this phone" (guide for an AI agent in Termux)

DualSub Replay's AI assistant can use the free models of the OpenCode CLI installed in Termux on
the same phone, with no key. This page is written so that an OpenCode agent running in that Termux
can check the setup, fix what it can, and report back. A person can follow it too.

To use it, open OpenCode in Termux and ask:

> Read https://raw.githubusercontent.com/hoangkien1703/dual-sub-replay/main/docs/opencode-termux-setup.md
> and follow it. Ask me before each command.

## How the app runs OpenCode

- The app sends Termux's `RUN_COMMAND` intent. Termux then runs, in the background and with no
  terminal:
  `/data/data/com.termux/files/usr/bin/bash -lc '<script>' dualsub-replay <model>`.
  The message goes on stdin, which needs Termux 0.118 or newer.
- This is a **login shell that is not interactive**. It reads `$PREFIX/etc/profile` and
  `~/.bash_profile` or `~/.profile`, but **not `~/.bashrc`**. Aliases and PATH lines in
  `~/.bashrc` are not seen.
- The script (from `app/src/main/java/com/kienhoang/dualsubreplay/assistant/AiOpenCodeTermux.kt`):

  ```sh
  PATH="$HOME/.opencode/bin:$HOME/.bun/bin:$HOME/.local/bin:$HOME/bin:$PATH"
  dir="$HOME/.dualsub-replay"
  mkdir -p "$dir" && cd "$dir" && printf '%s\n' '{"agent":{"plan":{"permission":"ask"}}}' > opencode.json || exit 90
  command -v opencode >/dev/null 2>&1 || exit 91
  exec opencode run --agent plan --format json -m "$1"
  ```

  Exit 90 shows "Termux couldn't run OpenCode", and exit 91 shows "OpenCode wasn't found in Termux".
  Builds before PR #118's second commit did not have the `PATH=` line.
- `~/.dualsub-replay/opencode.json` keeps OpenCode's own tools on "ask". A run with no terminal
  refuses every ask, so the model can only answer in text. Don't change this file: the app
  rewrites it before every run.

## Steps for the agent

Run each step and keep its output for the report.

1. **Termux version.** Run `echo "$TERMUX_VERSION"`. It must be 0.118 or newer. If it is older or
   empty, tell the user to update Termux (the F-Droid or GitHub build).
2. **Where OpenCode is.** Run `command -v opencode; opencode --version`.
   - If the path is not in `$PREFIX/bin`, check whether a background login shell can find it:
     `env -i HOME="$HOME" PREFIX="$PREFIX" PATH="$PREFIX/bin" bash -lc 'command -v opencode'`.
   - If that prints nothing, make OpenCode visible to every shell with a link in Termux's `bin`
     (ask the user first): `ln -sf "$(command -v opencode)" "$PREFIX/bin/opencode"`.
     If `command -v opencode` names an alias or function instead of a file, find the real file
     first (for example `type opencode`) and link that file.
3. **Other apps allowed.** Run `grep -n allow-external-apps ~/.termux/termux.properties`.
   - There must be a line `allow-external-apps=true` (or `allow-external-apps = true`), and no line
     setting it to `false`. Repeated identical lines are harmless.
   - If it is missing, run:
     `mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings`.
4. **Run what the app runs.** This uses the same shell, script, folder and model as the app, in an
   environment as bare as the app's:

   ```sh
   cat > "$TMPDIR/dualsub-check.sh" <<'EOF'
   PATH="$HOME/.opencode/bin:$HOME/.bun/bin:$HOME/.local/bin:$HOME/bin:$PATH"
   dir="$HOME/.dualsub-replay"
   mkdir -p "$dir" && cd "$dir" && printf '%s\n' '{"agent":{"plan":{"permission":"ask"}}}' > opencode.json || exit 90
   command -v opencode >/dev/null 2>&1 || exit 91
   exec opencode run --agent plan --format json -m "$1"
   EOF
   echo "Reply with exactly one word: OK" |
     env -i HOME="$HOME" PREFIX="$PREFIX" TMPDIR="$TMPDIR" PATH="$PREFIX/bin" \
     "$PREFIX/bin/bash" -lc "$(cat "$TMPDIR/dualsub-check.sh")" dualsub-replay opencode/deepseek-v4.1-flash-free
   echo "exit code: $?"
   ```

   Success is a JSON line with `"type":"text"` and exit code 0.
   - Exit 91: OpenCode is still not found, so go back to step 2.
   - An `"type":"error"` line: report its `message`. A "free tier" or "rate limit" message comes
     from OpenCode's servers, not from the phone.
   - If `opencode/deepseek-v4.1-flash-free` is unknown, run `opencode models` and try a model from
     that list whose name starts with `opencode/` and ends with `-free`.
5. **Clean up.** Run `rm -f "$TMPDIR/dualsub-check.sh"`. Leave `~/.dualsub-replay` in place, since
   the app uses it.

## Report

Tell the user, in a few lines:

- the Termux version and the OpenCode version and path;
- whether `allow-external-apps` is on;
- the exit code and the reply text from step 4, or the error message;
- anything you changed, such as a link added in step 2.

Then the user opens DualSub Replay → AI assistant → "OpenCode on this phone". They tap
**Allow** if it is not allowed yet, then tap **Connect**.

## Don't

- Don't edit `~/.dualsub-replay/opencode.json` or set any OpenCode permission to "allow" for it.
- Don't change OpenCode's login, keys or providers, and don't print keys or tokens.
- Don't install other packages unless the user asks.
