#!/usr/bin/env bash
# Exercises the Linux installer in a container running real systemd.
#   test/install/run.sh [--keep]
set -euo pipefail

# Container paths must not be rewritten by Git Bash; host paths must be, and in the other direction.
export MSYS_NO_PATHCONV=1
host() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
NAME=uploadguard-install-test

[[ -f $ROOT/target/uploadguard.jar ]] || { echo "== building the jar"; (cd "$ROOT" && mvn -q -B clean package); }

echo "== building the systemd image"
docker build -q -t "$NAME" "$(host "$HERE")" >/dev/null

docker rm -f "$NAME" >/dev/null 2>&1 || true
echo "== starting it"
docker run -d --name "$NAME" --privileged --cgroupns=host \
    -v /sys/fs/cgroup:/sys/fs/cgroup:rw "$NAME" >/dev/null

for _ in $(seq 1 30); do
    docker exec "$NAME" systemctl is-system-running >/dev/null 2>&1 && break
    sleep 1
done
echo "   systemd: $(docker exec "$NAME" systemctl is-system-running 2>&1 || true)"

echo "== staging the files the way the guide says"
docker exec "$NAME" mkdir -p /opt/uploadguard /staging
docker cp "$(host "$ROOT/target/uploadguard.jar")" "$NAME:/staging/"
for f in install.sh uninstall.sh run.sh uploadguard.service env.example; do
    docker cp "$(host "$ROOT/deploy/linux/$f")" "$NAME:/staging/"
done
docker cp "$(host "$HERE/checks.sh")" "$NAME:/checks.sh"
docker exec "$NAME" bash -c 'cp /staging/* /opt/uploadguard/'

echo
docker exec "$NAME" bash /checks.sh
status=$?

[[ ${1:-} == --keep ]] || docker rm -f "$NAME" >/dev/null
exit $status
