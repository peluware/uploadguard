#!/usr/bin/env bash
# Runs inside the systemd container. Exercises install.sh and uninstall.sh for real.
set -uo pipefail

APP=/opt/uploadguard
passed=0; failed=0

ok()   { passed=$((passed+1)); echo "  PASS  $1"; }
bad()  { failed=$((failed+1)); echo "  FAIL  $1${2:+   ($2)}"; }
check(){ if [[ $2 == "$3" ]]; then ok "$1"; else bad "$1" "expected '$3', got '$2'"; fi; }
has()  { if eval "$2" >/dev/null 2>&1; then ok "$1"; else bad "$1"; fi; }
hasnt(){ if eval "$2" >/dev/null 2>&1; then bad "$1"; else ok "$1"; fi; }

echo "== first install, on a clean system"
out=$(bash $APP/install.sh 2>&1); echo "$out" | sed 's/^/      /'
has   "creates the uploadguard system user"      "id -u uploadguard"
check "the user has no login shell"              "$(getent passwd uploadguard | cut -d: -f7)" "/usr/sbin/nologin"
has   "creates .env from the template"           "test -f $APP/.env"
check ".env mode"                                "$(stat -c '%a %U:%G' $APP/.env)" "640 root:uploadguard"
check "jar mode"                                 "$(stat -c '%a %U:%G' $APP/uploadguard.jar)" "644 root:root"
has   "enables the unit by absolute path"        "test -L /etc/systemd/system/multi-user.target.wants/uploadguard.service"
check "the unit lives in /opt, not /etc"         "$(readlink /etc/systemd/system/multi-user.target.wants/uploadguard.service)" "$APP/uploadguard.service"
hasnt "does not start before being configured"   "systemctl is-active --quiet uploadguard"
has   "tells you to set UPSTREAM_URL"            "grep -q 'Set UPSTREAM_URL' <<<\"\$out\""

echo
echo "== configure and start"
sed -i 's|^UPSTREAM_URL=.*|UPSTREAM_URL=http://127.0.0.1:9999|' $APP/.env
systemctl start uploadguard
sleep 2
has   "the service is active"                    "systemctl is-active --quiet uploadguard"
check "it runs as the uploadguard user"          "$(ps -o uid= -p "$(systemctl show -p MainPID --value uploadguard)" | tr -d ' ')" "$(id -u uploadguard)"
has   "the startup line is in the journal"       "journalctl -u uploadguard --no-pager | grep -q 'listening on port 8080'"

echo
echo "== smoke test, no signature and no reverse proxy"
mismatch=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: image/png' --data-binary @/bin/ls http://127.0.0.1:8080/x)
check "a binary declared as png is refused"      "$mismatch" "415"
printf '\x89PNG\r\n\x1a\n' > /tmp/ok.png
relayed=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: image/png' --data-binary @/tmp/ok.png http://127.0.0.1:8080/x)
if [[ $relayed != "415" ]]; then ok "real png is relayed (got $relayed from the dead upstream)"; else bad "real png is relayed" "got 415"; fi

echo
echo "== second install, nothing changed"
before=$(systemctl show -p ActiveEnterTimestamp --value uploadguard)
out=$(bash $APP/install.sh 2>&1); echo "$out" | sed 's/^/      /'
has   "reports nothing changed"                  "grep -q 'nothing changed' <<<\"\$out\""
has   "leaves .env alone"                        "grep -q 'left as it is' <<<\"\$out\""
check "does not restart the service"             "$(systemctl show -p ActiveEnterTimestamp --value uploadguard)" "$before"

echo
echo "== uninstall --keep-config"
echo "KEEPME=yes" >> $APP/.env
bash $APP/uninstall.sh --keep-config 2>&1 | sed 's/^/      /'
hasnt "the service is gone"                      "systemctl is-active --quiet uploadguard"
hasnt "the unit link is gone"                    "test -L /etc/systemd/system/multi-user.target.wants/uploadguard.service"
has   "keeps .env"                               "test -f $APP/.env"
has   "keeps what was in it"                     "grep -q KEEPME $APP/.env"
hasnt "removes the jar"                          "test -f $APP/uploadguard.jar"
has   "keeps the user that owns .env"            "id -u uploadguard"

echo
echo "== full uninstall"
cp /staging/* $APP/ && bash $APP/install.sh >/dev/null 2>&1
sed -i 's|^UPSTREAM_URL=.*|UPSTREAM_URL=http://127.0.0.1:9999|' $APP/.env
systemctl start uploadguard; sleep 1
bash $APP/uninstall.sh 2>&1 | sed 's/^/      /'
hasnt "the directory is gone"                    "test -d $APP"
hasnt "the user is gone"                         "id -u uploadguard"
hasnt "systemd no longer knows the unit"         "systemctl cat uploadguard"

echo
echo "== $passed passed, $failed failed"
exit $((failed > 0))
