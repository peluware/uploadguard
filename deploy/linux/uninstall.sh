#!/usr/bin/env bash
# Removes UploadGuard: the service, the systemd link, /opt/uploadguard and the system user.
#
#   sudo bash /opt/uploadguard/uninstall.sh                 remove everything
#   sudo bash /opt/uploadguard/uninstall.sh --keep-config    keep /opt/uploadguard/.env
#
# Idempotent as well: anything already gone is skipped.
set -euo pipefail

APP_DIR=/opt/uploadguard
USER=uploadguard
UNIT_NAME=uploadguard.service
KEEP_CONFIG=0

[[ ${1:-} == --keep-config ]] && KEEP_CONFIG=1
[[ $EUID -eq 0 ]] || { echo "run as root: sudo bash $0" >&2; exit 1; }

# Printed before anything is deleted, so the configuration is not lost in silence.
if [[ -f $APP_DIR/.env && $KEEP_CONFIG -eq 0 ]]; then
    echo "--- $APP_DIR/.env, about to be removed ---"
    cat "$APP_DIR/.env"
    echo "------------------------------------------"
fi

if systemctl is-active --quiet "$UNIT_NAME" 2>/dev/null; then
    echo "- stopping"
    systemctl stop "$UNIT_NAME"
fi

if systemctl is-enabled --quiet "$UNIT_NAME" 2>/dev/null; then
    echo "- disabling"
    systemctl disable "$UNIT_NAME" >/dev/null
fi

systemctl daemon-reload
systemctl reset-failed "$UNIT_NAME" 2>/dev/null || true

if [[ -d $APP_DIR ]]; then
    if [[ $KEEP_CONFIG -eq 1 ]]; then
        echo "- $APP_DIR (keeping .env)"
        # This script is inside the directory it is emptying, so it deletes itself last
        find "$APP_DIR" -mindepth 1 ! -name .env -delete
    else
        echo "- $APP_DIR"
        rm -rf "$APP_DIR"
    fi
fi

if id -u "$USER" >/dev/null 2>&1; then
    if [[ $KEEP_CONFIG -eq 1 ]]; then
        echo "= user $USER (kept, it owns .env)"
    else
        echo "- user $USER"
        userdel "$USER"
    fi
fi

echo "done"
