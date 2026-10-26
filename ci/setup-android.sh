#!/bin/sh
# Use the hosted image's existing SDK and licence. Never enroll new terms.
set -eu
: "${ANDROID_HOME:?Set Android SDK directory}"
MANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
test -x "$MANAGER" || { echo 'SDK image missing cmdline-tools/latest/bin/sdkmanager' >&2; exit 2; }
test -s "$ANDROID_HOME/licenses/android-sdk-license" || {
    echo 'SDK image licence missing; no terms accepted' >&2; exit 2;
}
case "${1:-build}" in
    build) set -- 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.0.12077973' ;;
    signing) set -- 'build-tools;35.0.0' ;;
    *) echo 'Unknown SDK preparation mode' >&2; exit 2 ;;
esac
exec "$MANAGER" --sdk_root="$ANDROID_HOME" --install "$@" </dev/null
