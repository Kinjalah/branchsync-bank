#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
mapfile -t sources < <(find src -name '*.java' -type f | sort)
if command -v javac >/dev/null 2>&1; then
  javac -encoding UTF-8 --release 17 --add-modules jdk.httpserver -d out "${sources[@]}"
else
  java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 --release 17 --add-modules jdk.httpserver -d out "${sources[@]}"
fi
printf 'BranchSync Bank build successful.\n'
