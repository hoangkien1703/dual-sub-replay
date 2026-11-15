# CI checks for AI requests, APK size and memory

## Status

Implemented. The owner asked on 2026-11-15 whether CI should test CPU, RAM and storage use and AI requests per minute, and agreed ("yes do what you recommend") to: a hard check on AI requests per minute, a tighter APK size limit, and memory as report only. No hard CPU or RAM gate: the existing `PageCpuUseTest` budget already fails now and then on slow runners. Merge needs the owner's OK.

## Context / problem

- Free Gemini keys allow about 10 to 15 requests a minute per model. On 2026-11-07 the background look-ahead sent a request for every sentence crossing the five-minute mark and free keys ran out. `AiBackgroundPrefetchTest` now caps the requests of 25 minutes of playback, but nothing checked requests **per minute per key**, or what retries and errors add on top.
- `tools/report_apk.py` failed only above 80 MB. The release APK is 27.7 MB (v1.9.2), so a change could add 50 MB unnoticed.
- Memory was measured only by hand (`tools/capture_performance.py`).

## Goals

1. A unit test fails when the app would ask one key for one model more than 10 times in any 60 s, during subtitle translation or AI subtitles, whatever the service answers.
2. CI fails when the release APK passes a limit close to today's size, and shows the change since the last release on every PR.
3. Every PR shows the emulator's memory and app data size next to main's, with a warning on a big jump, never a failure.

## Non-goals

- No CPU or RAM gate, no new device test job, no real network in tests.
- The AI assistant chat is not budgeted: it sends one request per message the person writes.
- No change to translation results, prompts or settings.

## User-visible behavior

None, except: after a model refuses the JSON schema or the thinking setting (once per model and app run), the request is sent again on the key's next turn (up to 6 s later) instead of at once. Google's lines show meanwhile, as before.

## Technical constraints / invariants

- Counting happens at the transport (`AiChatTransport`, `AiHttpPoster`): every request that would go over HTTP is counted, retries included. A real HTTP fake server (MockWebServer) was considered and dropped: it needs real time, so minutes of 6 s pacing would make the test slow and timing-dependent. Virtual time (`kotlinx-coroutines-test`, unit tests only) runs 30 minutes of pacing in milliseconds.
- The budget is per key and per model because Gemini's free limits are per model.
- The size and memory steps keep both CI job names, so the release gate (`tools/automatic_release.py`) and `pr-preview-auto.yml` are unchanged.

## Proposed approach / plan

1. `AiRequestBudgetTest` (unit test): the real `AiSubtitleTranslator`, keyed translation model and `AiKeyPacer` against a fake chat service; the real `AiVideoSubtitleMaker` and `AiSubtitleRunner` against a fake Gemini. Scenarios: one key and three keys while the player skips ahead every 3 s (the worst demand), every request answered with 503, 429, a refused schema, a rejected key; AI subtitles for an hour-long video, all answered, overloaded, or limited.
2. Fix what the test found: the schema and thinking retries were sent at once, 12 requests in a minute on one key. They now wait for the key's next turn.
3. `report_apk.py`: hard limit 32 MB (about 4 MB over v1.9.2), size table in the job summary with the change since the latest release (from the releases API), and a warning (not a failure) above 500 kB of growth. Raising the limit is a deliberate one-line change in `android.yml`.
4. `PageCpuUseTest` also logs PSS of the app and the page, and the size of the app's data folder, while the video plays. `tools/report_memory.py` puts them in the job summary, compares them with main's last green run (`memory-use` artifact) and warns above 25% growth. The step is `continue-on-error` and the script always exits 0.

## Acceptance criteria

- [x] `AiRequestBudgetTest` passes, and failed before the retry fix (12 requests in one minute on one key).
- [x] Each scenario stays at or under 10 requests a minute per key and model (measured: 10, 7, 6, 1, 10, 10, 6, 3).
- [x] `report_apk.py` writes the size table, warns on large growth and still fails only above the limit or on forbidden components (`tools/tests`).
- [x] `report_memory.py` never fails, even with no results or baseline (`tools/tests`).
- [ ] PR CI shows the size table and the memory table on this PR (memory: no baseline until this merges and main runs once).

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `./gradlew testDebugUnitTest` incl. `AiRequestBudgetTest`, `AiTranslationAccessTest`, `AiSubtitleTranslatorTest` | Cloud + PR CI |
| Tool tests | `python3 -m unittest discover -s tools/tests -p 'test_*.py'` | Cloud + PR CI |
| Workflow | YAML parses; the size step's release lookup run by hand returns `v1.9.2 27663792` | Cloud |
| Device | `PageCpuUseTest` logs a `MemoryUse` line; the job summary shows it | PR CI `managed-device-tests` |
| Size | Release APK built with test signing stays under 32 MB | Cloud + PR CI |
