# Continuous integration and releases

This page holds the CI and release details that used to live in the README. The
actual behavior is defined by [release-on-main.yml](../.github/workflows/release-on-main.yml)
and [automatic_release.py](../tools/automatic_release.py); contributors and agents also
follow [Preparing PRs for owner merge](../AGENTS.md#preparing-prs-for-owner-merge).

## Continuous integration

Pull requests use the committed wrapper to run formatting and complexity checks, unit tests, lint, debug and Android-test APK assembly, and an optimized release build. A second job executes the offline fixture suite on the API 36 managed device. Both jobs must pass before merging into `main`. Same-repository pull requests also publish a numbered test APK to the rolling preview release and remove it automatically when the PR is closed.

## Automatic official releases

When **hoangkien1703 merges a PR into `main`**, the **Release merged PR** workflow checks that the latest Android CI run for the PR's final commit succeeded, including all four jobs: `verify-build`, `managed-device-tests`, `fdroid-build` and `fdroid-device-tests`. It then builds the preview and a production-signed official APK. Direct pushes and merges by other accounts do not publish releases.

By default, each eligible merge increments the highest existing or reserved stable patch version: `1.0.4 → 1.0.5` (and `1.0.9 → 1.0.10`). It also increments Android's internal `versionCode` above all prior stable releases and reservations. Preview tags do not affect official version selection. **Do not manually bump the Gradle constants in feature PRs.**

Choose an override **before merging**:

| PR instruction | Result from 1.0.4 |
| --- | --- |
| None, or label `release:patch` | 1.0.5 |
| Label `release:minor` | 1.1.0 |
| Label `release:major` | 2.0.0 |
| A standalone `Release-Version: 1.2.0` line in the PR description | Exactly 1.2.0 |
| Label `release:skip` | Preview only; no official release |

Use at most one release label or one exact-version directive. Conflicting instructions, malformed versions, and versions no higher than the existing/reserved versions stop the release. Labels are created automatically when the new main workflow first runs; exact-version directives work without labels.

The workflow creates a **draft reservation** before building. The final tag points to a release-only commit whose parent is the exact merged commit; only `appVersionName` and `appVersionCode` differ. This keeps tagged source reproducible without pushing version commits to `main` or bypassing branch protection. The constants on `main` remain development defaults; release tags and APKs contain the actual release version.

The draft becomes public only after the production signature, package ID, version name/code, APK, and checksum have been verified and both assets uploaded. Release notes are generated from GitHub's merged-PR history. Releases queue one at a time (`queue: max`, up to GitHub's 100-pending-run limit) so simultaneous merges cannot reserve the same version. If runs arrive out of merge order, an older merge is prevented from publishing older source as a newer version.

**Recovery:** rerun the failed workflow, or choose **Actions → Release merged PR → Run workflow**, select `main`, and enter the merged PR number. A retry resumes the same draft, version, source commit, and `versionCode`; changing labels or the PR description after reservation does not change that release. Already-published merges are a no-op. Retrying an older reserved release never replaces a newer release as Latest. Keep the draft's hidden automation marker intact, and do not delete failed reservations: they prevent version reuse. If checks were not green at merge time, finish/rerun CI and then retry the release workflow.

Release automation regression tests run in PR CI. Run them locally with:

```bash
python3 -m unittest discover -s tools/tests -p 'test_*.py' -v
```
