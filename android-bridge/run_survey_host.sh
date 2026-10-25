#!/bin/sh
# Pure contracts with synthetic data and real existing radar coordinator; no IO adapters.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD=$(mktemp -d /tmp/casca-survey-host-XXXXXX)
trap 'rm -rf "$BUILD"' EXIT HUP INT TERM
java -m jdk.compiler/com.sun.tools.javac.Main -Xlint:all -Werror -d "$BUILD/classes" \
    "$ROOT/src/org/workspace/band7/RadarSession.java" \
    "$ROOT/src/org/workspace/band7/SurveySession.java" \
    "$ROOT/tests/org/workspace/band7/RadarSessionTest.java" \
    "$ROOT/tests/org/workspace/band7/SurveySessionTest.java"
java -cp "$BUILD/classes" org.workspace.band7.RadarSessionTest
java -cp "$BUILD/classes" org.workspace.band7.SurveySessionTest
