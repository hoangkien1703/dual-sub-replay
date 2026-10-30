# Remote switches

The app reads [`switches.json`](switches.json) from `main` once a day while Settings → App updates →
Tell me about new versions is on (owner decision 2026-10-30,
[spec](../docs/specs/2026-10-30-scale-readiness.md)). It can only choose among behaviour already in
the app, so a fix for a YouTube or Google change reaches phones without a new APK. It never carries
code, scripts, selectors or new addresses; at worst it turns something off. A file the app cannot
read, or one with another `schema`, changes nothing, and a phone forgets the switches 30 days after
it last read them.

Change it through a normal PR. A PR that changes only this folder gets `release:skip` (it does not
change the APK), and phones pick the change up within a day of the merge.

## Format

```json
{
  "schema": 1,
  "rules": [
    {
      "min_version_code": 1,
      "max_version_code": 60,
      "switches": {
        "google_translate_addresses": ["EXTENSION_BATCH", "BATCH", "WEB_BATCH"],
        "background_translate_pause_seconds": 10,
        "skip_ads": "off",
        "skip_sponsors": "off",
        "page_token_captions": "off",
        "notice": "youtube_changed",
        "notice_key": "2026-11-02"
      }
    }
  ]
}
```

Rules apply in order to apps whose version code is within `min_version_code`..`max_version_code`
(both optional); a later rule's value replaces an earlier one. Every switch is optional.

| Switch | Values | Effect |
| --- | --- | --- |
| `google_translate_addresses` | Names from `GoogleEndpoint` in `translation/GoogleWebTranslator.kt`: `BATCH`, `EXTENSION_BATCH`, `SINGLE`, `WEB_BATCH` | The Google Translate addresses to try, in this order; others are not used. Unknown names are ignored; an empty list keeps the app's own order. |
| `background_translate_pause_seconds` | 3 to 60 | A slower pace for translating minutes 2 to 5 in the background (the app's own is 3 s). Out of range: ignored. |
| `skip_ads`, `skip_sponsors`, `page_token_captions` | `"off"` or `"default"` | `"off"` turns that feature off whatever the user chose; `"default"` gives it back to the user's setting. |
| `notice` | `youtube_changed`, `google_translate_limited`, `update_recommended` | Shows that notice (its text is in the app's strings, in every interface language). An unknown name shows nothing. |
| `notice_key` | Up to 40 letters, digits, `.`, `_` or `-` | Tells one incident apart from an earlier one with the same notice, so a notice closed before shows again. |

A rule with `"notice": ""` clears a notice set by an earlier rule.
