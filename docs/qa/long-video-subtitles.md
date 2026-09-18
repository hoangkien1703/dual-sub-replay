# Subtitles for long videos

## Behavior

Subtitles follow the player's actual position. No translation starts until a playback position is known. The app prepares the current cue first, then up to 60 seconds ahead and 30 seconds behind, with a hard limit of 96 loaded entries even for dense transcripts. Once that window is ready the worker suspends until playback changes; it never continues through the remaining video in the background.

Seeking replaces the pending window. At most the current ML Kit request is allowed to finish; its result cannot publish at an obsolete seek position. Pausing or backgrounding stops additional work. Leaving a video cancels its session and its in-flight caption HTTP request. Changing caption format or target language replaces the translation session; returning to a previously translated sentence uses the cache.

Source and display transcripts are held in temporary indexed disk files, with only timestamps/offsets and the current window retained in memory. IDs and original word timestamps survive window reads, preserving replay and karaoke timing. The UI receives a snapshot of at most 96 entries, instead of a copy of the entire transcript after every translation.

Translation uses one lazily opened ML Kit client per session, plus the existing bounded memory cache and a persistent LRU cache limited to 2,048 entries / 4 MiB of UTF-8 translated text. Cache keys include exact source text and both languages. Transcript files are removed on session exit and leftovers are cleared when a new ViewModel first opens its transcript storage.

## Fetching limitation

The existing YouTube caption endpoint returns a complete timed-text document. This change does not assume undocumented time-range pagination or send speculative range parameters. The 8 MiB response cap remains. Downloading, parsing, merging, and initially indexing that document still require a temporary full source representation, now off the main thread; the resulting full text/word graphs are not retained during playback. Expanded transcript files are capped at 64 MiB each and 200,000 entries. Disk work and cache reads/writes run on IO dispatchers.

## Regression coverage

- A synthetic 23,655-entry transcript: start ten hours in, translate only the bounded window, then suspend.
- Seek twelve hours ahead during an in-flight translation; reject obsolete publication and start at the new position.
- Pause during translation; allow only that entry to finish, then resume on demand.
- Cancellation even when a provider finishes after cancellation.
- Unknown playback position and initially paused playback do not start translation.
- Dense 100,000-entry transcript: windows remain bounded and contain the current cue.
- Unicode, cue IDs, and word-timing round trips, empty tracks, and transcript cleanup.
- Persistent cache reuse, language/text isolation, LRU entry eviction, UTF-8 byte caps, missing files, and interrupted writes.

## Device acceptance

Use the reported 22-hour video on the OnePlus Ace 5. Check playback from the start and a resumed position, seek to hours 10 and 20 and back, pause for a minute, background/return, leave the video, change both subtitle languages and presentation formats, and check replay and karaoke in portrait and landscape. Capture a crash log and memory/CPU evidence if a failure remains. Automated fixtures do not prove battery savings or reproduce the original device crash.
