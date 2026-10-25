#!/bin/sh
# Reuse real compiler/linker modules from this reduced Debian Java image.
# No download, compiler stub, trust-store change, or modification of the source JDK.
set -eu
: "${JAVA_SOURCE_HOME:?Set to the existing Java21 image}"
: "${JAVA_COPY_HOME:?Set to a new writable destination}"
test ! -e "$JAVA_COPY_HOME"
"$JAVA_SOURCE_HOME/bin/java" -m jdk.compiler/com.sun.tools.javac.Main --version
"$JAVA_SOURCE_HOME/bin/java" -m jdk.jlink/jdk.tools.jlink.internal.Main --version
cp -a "$JAVA_SOURCE_HOME" "$JAVA_COPY_HOME"
for tool in javac jlink jmod; do
    case "$tool" in
        javac) module=jdk.compiler/com.sun.tools.javac.Main ;;
        jlink) module=jdk.jlink/jdk.tools.jlink.internal.Main ;;
        jmod) module=jdk.jlink/jdk.tools.jmod.Main ;;
    esac
    printf '#!/bin/sh\nexec "$(dirname "$0")/java" -m %s "$@"\n' "$module" > "$JAVA_COPY_HOME/bin/$tool"
    chmod 755 "$JAVA_COPY_HOME/bin/$tool"
done
cmp "$JAVA_SOURCE_HOME/lib/modules" "$JAVA_COPY_HOME/lib/modules"
"$JAVA_COPY_HOME/bin/javac" --version
"$JAVA_COPY_HOME/bin/jlink" --version
