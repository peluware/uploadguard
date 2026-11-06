#!/usr/bin/env bash
# Runs UploadGuard in the foreground, reading .env from this directory if it is there.
# Useful to try it without installing the service.
set -euo pipefail

cd "$(dirname "$0")"

if [[ ! -f uploadguard.jar ]]; then
    echo "uploadguard.jar is not in $PWD." >&2
    echo "Build it with 'mvn clean package' and copy target/uploadguard.jar here." >&2
    exit 1
fi

# KEY=VALUE per line, # for comments
if [[ -f .env ]]; then
    set -a
    # shellcheck disable=SC1091
    source ./.env
    set +a
fi

: "${UPSTREAM_URL:?set it in $PWD/.env or in the environment}"

exec java -Djdk.httpclient.allowRestrictedHeaders=host -Xmx256m -jar "$PWD/uploadguard.jar"
