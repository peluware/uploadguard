#!/usr/bin/env bash
# Installs or updates UploadGuard. Runs in place: this script lives in /opt/uploadguard together with
# the jar and the unit file, and installs what is already next to it. Nothing is copied from anywhere.
#
#   sudo bash /opt/uploadguard/install.sh
#
# Idempotent: run it as many times as you like. .env is never touched once it exists, and the service
# is restarted only if something actually changed. A "=" means already in place, a "+" means changed.
set -euo pipefail

APP_DIR=/opt/uploadguard
USER=uploadguard
UNIT_NAME=uploadguard.service

HERE="$(cd "$(dirname "$0")" && pwd)"

[[ $EUID -eq 0 ]] || { echo "run as root: sudo bash $0" >&2; exit 1; }

if [[ $HERE != "$APP_DIR" ]]; then
    echo "this script has to run from $APP_DIR, not from $HERE" >&2
    echo "the unit file refers to $APP_DIR by absolute path" >&2
    exit 1
fi

[[ -f $APP_DIR/uploadguard.jar ]] || { echo "uploadguard.jar is missing from $APP_DIR" >&2; exit 1; }

# systemd needs an absolute path and does not read PATH, so the unit cannot just say "java".
# /usr/bin/java first when it exists: on a packaged JDK that is the alternatives symlink, and it keeps
# working across JDK upgrades. Otherwise whatever is on PATH, or the usual places a JDK unpacks into.
if [[ -x /usr/bin/java ]]; then
    JAVA_BIN=/usr/bin/java
else
    JAVA_BIN="$(command -v java || true)"
    if [[ -z $JAVA_BIN ]]; then
        for candidate in /usr/lib/jvm/*/bin/java /opt/java/*/bin/java /usr/local/bin/java; do
            [[ -x $candidate ]] && { JAVA_BIN="$candidate"; break; }
        done
    fi
fi
[[ -n ${JAVA_BIN:-} && -x $JAVA_BIN ]] || {
    echo "no java executable found; install a JDK 21+ or put java on root's PATH" >&2
    exit 1
}

changed=0

# --- system user the service runs as --------------------------------------
if id -u "$USER" >/dev/null 2>&1; then
    echo "= user $USER"
else
    echo "+ user $USER"
    useradd --system --no-create-home --shell /usr/sbin/nologin "$USER"
fi

# --- configuration, kept across upgrades ----------------------------------
if [[ -f $APP_DIR/.env ]]; then
    echo "= $APP_DIR/.env (left as it is)"
else
    echo "+ $APP_DIR/.env"
    cp "$APP_DIR/env.example" "$APP_DIR/.env"
    changed=1
    needs_config=1
fi

# --- ownership and modes --------------------------------------------------
# The service only ever reads. root owns everything; the service user reads .env and nothing else.
chown -R root:root "$APP_DIR"
chmod 755 "$APP_DIR"
chmod 644 "$APP_DIR"/*.jar "$APP_DIR"/*.service "$APP_DIR"/env.example
# scp does not carry the exec bit over, so the scripts arrive unreadable to the shell
chmod 755 "$APP_DIR"/*.sh
[[ -f $APP_DIR/README.md ]] && chmod 644 "$APP_DIR/README.md"
chown "root:$USER" "$APP_DIR/.env"
chmod 640 "$APP_DIR/.env"

# --- java path in the unit ------------------------------------------------
current_java="$(awk -F'=' '/^ExecStart=/ { split($2, a, " "); print a[1]; exit }' "$APP_DIR/$UNIT_NAME")"
if [[ $current_java == "$JAVA_BIN" ]]; then
    echo "= ExecStart uses $JAVA_BIN"
else
    echo "+ ExecStart -> $JAVA_BIN"
    sed -i "s|^ExecStart=[^ ]*|ExecStart=$JAVA_BIN|" "$APP_DIR/$UNIT_NAME"
    changed=1
fi

# --- systemd unit ---------------------------------------------------------
# Enabled by absolute path, so the unit file stays in /opt/uploadguard and systemd only keeps a
# symlink of its own under /etc.
if [[ -L /etc/systemd/system/multi-user.target.wants/$UNIT_NAME ]]; then
    echo "= $UNIT_NAME enabled"
else
    echo "+ $UNIT_NAME enabled"
    systemctl enable "$APP_DIR/$UNIT_NAME"
    changed=1
fi
systemctl daemon-reload

# --- start or restart -----------------------------------------------------
if [[ ${needs_config:-0} -eq 1 ]]; then
    echo
    echo "  Set UPSTREAM_URL before starting:"
    echo "      sudo nano $APP_DIR/.env"
    echo "      sudo systemctl start $UNIT_NAME"
    echo
    exit 0
fi

if [[ $changed -eq 1 ]] || ! systemctl is-active --quiet "$UNIT_NAME"; then
    echo "+ restarting"
    systemctl restart "$UNIT_NAME"
else
    echo "= already running, nothing changed"
fi

sleep 1
systemctl --no-pager --lines=15 status "$UNIT_NAME" || true
