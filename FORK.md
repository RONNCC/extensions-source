# RONNCC fork extension repo

This fork builds only the extensions listed in [`fork-extensions.txt`](fork-extensions.txt) and
publishes them as its own Mihon extension repo. Upstream CI workflows were removed; the only
workflow is `.github/workflows/fork_publish.yml`.

## Add the repo in Mihon

```
https://github.com/RONNCC/extensions-source/raw/repo/index.pb
```

Mihon (`ExtensionStoreService`) also accepts `.../raw/repo/repo.json`, `.../raw/repo/index.json` and
`.../raw/repo/index.min.json`; `index.json` is read directly and `index.min.json` (just `[]`) leads
to `index.pb` through `repo.json`'s `index_v2`. The repo only exists after the first successful run of the
**Fork publish** workflow. Extensions use extension lib 1.6 and need Mihon 0.20.1+.

## How it works

- Triggers: push to `main` or `fork-release`, or manual run (`Actions > Fork publish > Run workflow`;
  the workflow file must be on the default branch for manual runs).
- Builds `assembleRelease` for every module in `fork-extensions.txt` (one `src/<lang>/<name>` per line;
  `#` comments allowed). Add a line when a fix changes an extension, remove it when upstream ships the fix.
  Bump `versionCode` in the extension's `build.gradle.kts` so Mihon sees an update.
- Signs with the fork keystore from the Actions secrets `SIGNING_KEY` (base64 PKCS12), `ALIAS`,
  `KEY_STORE_PASSWORD`, `KEY_PASSWORD`. Never commit a keystore (`*.jks` is gitignored).
- Uploads APK + JAR to a GitHub release tagged with the short commit sha, then commits
  `index.pb`, `index.json`, `repo.json`, `index.html`, `release-assets.json` to the orphan branch `repo`.
- The index contains only the extensions from `fork-extensions.txt`.

## Different signing key

Fork APKs are signed with the fork's key, not Keiyoushi's. Android refuses to update an app
signed with another key, and Mihon trusts extensions per signing key. So:

1. Uninstall the Keiyoushi version of each fork extension (same package name) first.
2. Install from this repo and trust the fork key when Mihon asks. The key's SHA-256 fingerprint
   is the `signingKeyFingerprint` in `repo.json`.
3. Do not install those extensions from the Keiyoushi repo again, or Mihon will offer the
   other-signed build as an update/conflict.
