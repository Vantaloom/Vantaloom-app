# Android release signing

The APK is signed in CI with a keystore supplied via **GitHub Actions secrets** —
**never** committed to the repo.

> The old `signing/vantaloom.keystore` (a default Android *debug* key: alias
> `androiddebugkey`, password `android`) was **deleted**. A committed debug key is
> public, so anyone could forge a "signed" update. Because the signing key is
> changing, **installed users must uninstall the old app once and reinstall** —
> Android refuses to update an APK whose signature changed. This is a one-time cost.

## 1. Generate a new private keystore (once, locally — keep it OFF the repo)

```bash
keytool -genkeypair -v \
  -keystore vantaloom-release.jks \
  -alias vantaloom \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 'YOUR_STORE_PASSWORD' \
  -keypass  'YOUR_KEY_PASSWORD' \
  -dname 'CN=Vantaloom, O=Timefiles, C=CN'
```

Store the two passwords in a password manager. Back up `vantaloom-release.jks`
somewhere private — **losing it means future updates can no longer install over
existing installs** (users would have to uninstall/reinstall again).

## 2. Base64-encode the keystore

```bash
# Linux / macOS
base64 -w0 vantaloom-release.jks > keystore.b64
```
```powershell
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('vantaloom-release.jks')) | Set-Content -NoNewline keystore.b64
```

## 3. Set the four GitHub Actions secrets

Repo → Settings → Secrets and variables → Actions → *New repository secret*:

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64`   | contents of `keystore.b64` |
| `SIGNING_KEYSTORE_PASSWORD` | the `-storepass` value |
| `SIGNING_KEY_ALIAS`         | `vantaloom` (the `-alias`) |
| `SIGNING_KEY_PASSWORD`      | the `-keypass` value |

`.github/workflows/build-apk.yml` decodes the keystore to a temp file and runs
`apksigner` with these secrets. If any are unset, the signing step fails loudly
(the build never ships an unsigned or debug-signed APK).

## 4. Delete the local plaintext copies you don't need

`keystore.b64` can be deleted after the secret is set. Keep only the private,
backed-up `vantaloom-release.jks`.
