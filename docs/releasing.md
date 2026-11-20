# Continuous integration and releases

This page holds the CI and release details that used to live in the README. The
actual behavior is defined by [release-on-main.yml](../.github/workflows/release-on-main.yml)
and [automatic_release.py](../tools/automatic_release.py); contributors and agents also
follow [Preparing PRs for owner merge](../AGENTS.md#preparing-prs-for-owner-merge).

## Continuous integration

Pull requests use the committed wrapper to run formatting and complexity checks, unit tests, lint, debug and Android-test APK assembly, and an optimized release build. A second job executes the offline fixture suite on the API 36 managed device at the same time. Both jobs must pass before merging into `main`. The same checks also run after every push to `main`, so the Gradle cache that pull requests restore stays current. Pull requests also publish a numbered test APK to the rolling preview release as soon as the first job passes (fork PRs only with the owner's `publish-preview` label), while the device tests still run, and remove it automatically when the PR is closed.

## Automatic official releases

When **hoangkien1703 merges a PR into `main`**, the **Release merged PR** workflow checks that the latest Android CI run for the PR's final commit succeeded, including both jobs: `verify-build` and `managed-device-tests`. It then builds a production-signed official APK. Merges no longer publish a preview APK of `main`; the preview release only holds open PRs' test APKs. Direct pushes and merges by other accounts do not publish releases.

By default, each eligible merge increments the highest existing or reserved stable patch version: `1.0.4 → 1.0.5` (and `1.0.9 → 1.0.10`). It also increments Android's internal `versionCode` above all prior stable releases and reservations. Preview tags do not affect official version selection. **Do not manually bump the Gradle constants in feature PRs.**

Choose an override **before merging**:

| PR instruction | Result from 1.0.4 |
| --- | --- |
| None, or label `release:patch` | 1.0.5 |
| Label `release:minor` | 1.1.0 |
| Label `release:major` | 2.0.0 |
| A standalone `Release-Version: 1.2.0` line in the PR description | Exactly 1.2.0 |
| Label `release:skip` | No release |

Use at most one release label or one exact-version directive. Conflicting instructions, malformed versions, and versions no higher than the existing/reserved versions stop the release. Labels are created automatically when the new main workflow first runs; exact-version directives work without labels.

**Security fixes** (owner decision 2026-10-30, [spec](specs/2026-10-30-scale-readiness.md)): add the separate label `security:critical` (it combines with any release label) to a PR that fixes a security problem. Its reservation records `"security": true` and its release notes start with a note that it fixes a security problem (the app's Security update dialog links to that page under What was fixed; add a short summary in general terms, without steps to exploit it, and publish full details in a GitHub security advisory once most people have updated). Every release from then on publishes `update-info.json` with that release's `versionCode` as `minimum_safe_version_code`. Installed apps read that file once a day (Settings → App updates → Tell me about new versions, on by default): an app older than the latest security release shows a Security update dialog at once (then daily for 3 days, then weekly, until it updates), even after later ordinary releases; other new versions show a small banner at most once a week.

The workflow creates a **draft reservation** before building. The final tag points to a release-only commit whose parent is the exact merged commit; only `appVersionName` and `appVersionCode` differ. This keeps tagged source reproducible without pushing version commits to `main` or bypassing branch protection. The constants on `main` remain development defaults; release tags and APKs contain the actual release version.

The draft becomes public only after the production signature, package ID, version name/code, APK, and checksum have been verified and the APK, its checksum and `update-info.json` (version, `versionCode`, minimum safe `versionCode`, SHA-256 and size, written by `tools/automatic_release.py update-info`) uploaded. Before that, the workflow downloads the last 15 releases' APKs and makes `update-from-vX.Y.Z.patch` assets with `tools/apk_patch.py` (owner pick 2026-11-08, [spec](specs/2026-11-08-smaller-app-updates.md)); they are listed in `update-info.json`'s `patches`, are best effort (a failure publishes the release without them) and are not required for publication. The release APK carries ARM libraries only. A draft reserved before `update-info.json` existed builds from source whose release tool cannot write it, so such a retry fails; reserve it again with a new merge. Release notes are generated from GitHub's merged-PR history. Releases queue one at a time (`queue: max`, up to GitHub's 100-pending-run limit) so simultaneous merges cannot reserve the same version. If runs arrive out of merge order, an older merge is prevented from publishing older source as a newer version.

**Signing key protection** (owner decision 2026-11-02): the four `ANDROID_RELEASE_*` secrets are stored in the `release` environment (Settings → Environments → release), not as repository secrets. Only the `publish-main` job uses that environment, and its deployment branch rule allows `main` only, so a workflow pushed on any other branch, by a person, a bot or a leaked token, cannot read the production signing key. `main` itself only changes through a PR with green CI (ruleset *Protect main with verified PR CI*, no bypass). The release job also refuses to publish an APK whose certificate SHA-256 is not the production key registered in Android Developer Console (`F6:64:08:5B:…:2A:11:6C`). The owner keeps an offline, password-locked backup of the keystore and its passwords: Android only installs an update signed with the same key.

**The key never meets Gradle** (security review 2026-11-18, [spec](specs/2026-11-18-security-review-fixes.md)): `build-release` builds `app-release-unsigned.apk` with no secrets and hands it over as a one-day artifact. Then `publish-main`, in the `release` environment, signs it with the Android SDK's own `zipalign` and `apksigner` and runs the checks above. Every Gradle plugin, build script and downloaded library runs only in the job without the key.

**Preview signing key** (same review): PR CI signs its preview APK with that runner's throwaway debug key, because it runs the PR's own code, fork PRs too. The key that lets a preview update itself lives in the `preview` environment (deployment branches: `main` only) as `PREVIEW_KEYSTORE_BASE64`, `PREVIEW_KEYSTORE_PASSWORD` and `PREVIEW_KEY_ALIAS`, and only `publish-pr-preview` in `pr-preview-auto.yml` uses it, to re-sign own-branch previews before upload. Fork PRs build the package `com.kienhoang.dualsubreplay.forkpreview`, which never updates itself and can never install over a normal preview. Their APK goes on the preview release only after the owner adds the `publish-preview` label and re-runs the PR's Android CI. To make a preview key (once, on the owner's computer): `keytool -genkeypair -keystore preview.jks -alias preview -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=DualSub Replay preview"`, then paste the file's base64, the password and the alias into the three secrets. A new key means testers uninstall the preview once.

**Recovery:** rerun the failed workflow, or choose **Actions → Release merged PR → Run workflow**, select `main`, and enter the merged PR number. A retry resumes the same draft, version, source commit, and `versionCode`; changing labels or the PR description after reservation does not change that release. Already-published merges are a no-op. Retrying an older reserved release never replaces a newer release as Latest. Keep the draft's hidden automation marker intact, and do not delete failed reservations: they prevent version reuse. If checks were not green at merge time, finish/rerun CI and then retry the release workflow.

Release automation regression tests run in PR CI. Run them locally with:

```bash
python3 -m unittest discover -s tools/tests -p 'test_*.py' -v
```
