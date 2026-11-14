#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_ROOT="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mapfile -t SOURCES < <(find "$ROOT/app/src/main/cpp/lame" -maxdepth 1 -name '*.c' -print | sort)
cc -shared -fPIC -O1 -std=c99 \
  -DHAVE_STDINT_H -DHAVE_INTTYPES_H -DSTDC_HEADERS -Dieee754_float32_t=float \
  -I"$JAVA_ROOT/include" -I"$JAVA_ROOT/include/linux" \
  -I"$ROOT/app/src/main/cpp" -I"$ROOT/app/src/main/cpp/lame" \
  "$ROOT/app/src/main/cpp/mp3encoder.c" "${SOURCES[@]}" -lm -o "$WORK/libtunegrab_lame.so"
javac -d "$WORK" "$ROOT/tools/native-tests/Mp3Encoder.java"
java -Dtunegrab.native.library="$WORK/libtunegrab_lame.so" -cp "$WORK" com.tunegrab.app.audio.Mp3Encoder
