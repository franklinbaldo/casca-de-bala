#!/bin/sh
# One-time: create the F-Droid repo index key on a trusted machine.
# It signs only the repository index, never an APK. Keep a backup with the
# APK key custody; losing it means users re-add the repo, not reinstall.
set -eu
OUT=${1:?Usage: sh ci/fdroid-repo-key.sh /private/dir}
test ! -e "$OUT/casca-fdroid-repo.p12" || { echo 'Key already exists; refusing to overwrite' >&2; exit 2; }
mkdir -p "$OUT" && chmod 700 "$OUT"
umask 077
PASS=$(head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 40)
printf '%s\n' "$PASS" > "$OUT/casca-fdroid-repo.password"
keytool -genkeypair -noprompt -storetype PKCS12 -keystore "$OUT/casca-fdroid-repo.p12" \
    -storepass:file "$OUT/casca-fdroid-repo.password" -alias casca-fdroid-repo \
    -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=Casca de Bala F-Droid repo'
base64 -w0 < "$OUT/casca-fdroid-repo.p12" > "$OUT/casca-fdroid-repo.p12.b64"
cat <<MSG
Created in $OUT (mode 700). Add to the android-release environment:
  secret CASCA_FDROID_KEYSTORE_B64       <- contents of casca-fdroid-repo.p12.b64
  secret CASCA_FDROID_KEYSTORE_PASSWORD  <- contents of casca-fdroid-repo.password
  variable CASCA_FDROID_READY = true     (repository level, like CASCA_ACTIONS_SIGNING_READY)
Back up casca-fdroid-repo.p12 and the password, then delete $OUT.
MSG
