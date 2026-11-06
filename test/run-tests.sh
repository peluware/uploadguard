#!/usr/bin/env sh
# Runs the end-to-end suite. Needs Docker, curl and python3.
cd "$(dirname "$0")" || exit 1
exec python3 run-tests.py "$@"
