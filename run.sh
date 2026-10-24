#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
exec java --add-modules jdk.httpserver -cp out branchsync.web.ApiServer
