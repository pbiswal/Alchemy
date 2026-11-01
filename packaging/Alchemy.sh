#!/bin/sh
#
# POSIX launcher for Alchemy. Installed as the file "Alchemy" at the root of the
# distribution, next to alchemy.jar, lib/ and modules/.
#
# Alchemy resolves ./modules, ./shapes and ./log relative to the *working
# directory* (Alchemy.java: new File("modules")), so the launcher has to change
# into the installation directory rather than only locating the jar. Double
# clicking alchemy.jar works for the same reason: the JVM's working directory is
# then the directory holding the jar.
#
# Environment:
#   JAVA_HOME      used when set, otherwise "java" from PATH is used
#   ALCHEMY_OPTS   extra JVM options, e.g. ALCHEMY_OPTS="-Xmx1g -Duser.language=de"
#                  (intentionally word-split, so do not quote paths with spaces)
#
# Copyright (c) 2007-2010 Karl D.D. Willis. Licensed under the GPLv3 - see
# LICENSE.md.

APPDIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
cd "$APPDIR" || exit 1

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

# JPen loads its JNI library with System.loadLibrary from an unnamed module.
# JDK 18+ warns about that and will eventually block it; JDK 17 does not know the
# option at all and would refuse to start. Probe instead of testing the version
# string: one extra JVM start (tens of milliseconds) and the launcher cannot get
# the arithmetic wrong on a JDK it has never seen.
NATIVE_ACCESS=
if "$JAVACMD" --enable-native-access=ALL-UNNAMED -version >/dev/null 2>&1; then
    NATIVE_ACCESS=--enable-native-access=ALL-UNNAMED
fi

# $ALCHEMY_OPTS is deliberately unquoted so several options can be passed.
exec "$JAVACMD" -Xmx512m $NATIVE_ACCESS $ALCHEMY_OPTS \
    -Djava.library.path="$APPDIR/lib/jpen" \
    -jar "$APPDIR/alchemy.jar" "$@"
