#!/usr/bin/env bash
# Lazy Fitness: one-shot setup for a fresh Ubuntu or Debian VM (built for a Google Compute Engine e2-micro).
# Safe to run again: a second run updates the code and keeps your token, invite code and data.
#
#   With a free DuckDNS name (stable HTTPS address, recommended):
#     sudo DOMAIN=yourname.duckdns.org DUCKDNS_TOKEN=your-duckdns-token bash deploy/gcp/setup.sh
#   With your own domain already pointing at the VM:
#     sudo DOMAIN=api.example.com bash deploy/gcp/setup.sh
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "Run this with sudo."; exit 1; }
command -v apt-get >/dev/null 2>&1 || { echo "This script is for Ubuntu or Debian."; exit 1; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/../../backend"
[ -f "$SRC/server.py" ] || { echo "Cannot find backend/server.py. Run from the unzipped lazy-fitness folder."; exit 1; }

APP=/opt/lazy-fitness
DATA=/var/lib/lazy-fitness
BAK=/var/backups/lazy-fitness
ENVF=/etc/lazy-fitness.env
DOMAIN="${DOMAIN:?Set DOMAIN, for example DOMAIN=yourname.duckdns.org (HTTPS needs a name)}"
DUCKDNS_TOKEN="${DUCKDNS_TOKEN:-}"

echo "[1/8] Installing packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y python3 curl ca-certificates
apt-get install -y caddy

echo "[2/8] Adding 1 GB swap (the free VM has 1 GB of RAM)"
if ! swapon --show | grep -q .; then
  fallocate -l 1G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "[3/8] Installing the app"
id lazyfit >/dev/null 2>&1 || useradd --system --home-dir "$APP" --shell /usr/sbin/nologin lazyfit
mkdir -p "$APP" "$DATA" "$BAK"
rm -rf "$APP/backend"
cp -r "$SRC" "$APP/backend"
rm -rf "$APP/backend/__pycache__" "$APP/backend/.admin_token" "$APP/backend/.invite_code" "$APP/backend/.tunnel.log"
chown -R root:root "$APP"
chown lazyfit:lazyfit "$DATA"
chmod 750 "$DATA"
PY=/usr/bin/python3

echo "[4/8] Writing settings to $ENVF"
if [ ! -f "$ENVF" ]; then
  umask 077
  cat > "$ENVF" <<EOF
HOST=127.0.0.1
PORT=8080
FITCOACH_DB=$DATA/fitcoach.db
ADMIN_TOKEN=$(python3 -c 'import secrets;print(secrets.token_urlsafe(24))')
INVITE_CODE=$(python3 -c 'import secrets;print(secrets.token_hex(3))')
MAX_USERS=10
# To switch on AI plans, photo reading and step rewrites, remove the # on these three and fill them in,
# then: sudo systemctl restart lazy-fitness. More settings are in the main README.
# AI_BASE_URL=https://api.example.com/v1
# AI_API_KEY=
# AI_MODEL=
EOF
fi
chmod 600 "$ENVF"

echo "[5/8] Creating the service (starts on boot, restarts if it crashes)"
cat > /etc/systemd/system/lazy-fitness.service <<EOF
[Unit]
Description=Lazy Fitness backend
After=network-online.target
Wants=network-online.target

[Service]
User=lazyfit
Group=lazyfit
WorkingDirectory=/opt/lazy-fitness/backend
EnvironmentFile=/etc/lazy-fitness.env
ExecStart=$PY server.py
Restart=always
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/lazy-fitness

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable lazy-fitness >/dev/null 2>&1
systemctl restart lazy-fitness

echo "[6/8] Setting up the nightly backup (keeps 14 days in $BAK)"
cat > /usr/local/bin/lazy-fitness-backup <<'EOF'
#!/usr/bin/env python3
import datetime, glob, os, sqlite3
src = "/var/lib/lazy-fitness/fitcoach.db"
dst = "/var/backups/lazy-fitness/fitcoach-%s.db" % datetime.date.today()
if os.path.exists(src):
    s, d = sqlite3.connect(src), sqlite3.connect(dst)
    s.backup(d)
    d.close(); s.close()
for f in sorted(glob.glob("/var/backups/lazy-fitness/fitcoach-*.db"))[:-14]:
    os.remove(f)
EOF
chmod 755 /usr/local/bin/lazy-fitness-backup
echo '0 3 * * * root /usr/local/bin/lazy-fitness-backup' > /etc/cron.d/lazy-fitness-backup

echo "[7/8] Setting up HTTPS for $DOMAIN"
if [ -n "$DUCKDNS_TOKEN" ]; then
  SUB="${DOMAIN%.duckdns.org}"
  umask 077
  printf 'SUB=%s\nTOKEN=%s\n' "$SUB" "$DUCKDNS_TOKEN" > /etc/lazy-fitness-duckdns
  cat > /usr/local/bin/lazy-fitness-duckdns <<'EOF'
#!/bin/sh
. /etc/lazy-fitness-duckdns
curl -fsS -m 20 "https://www.duckdns.org/update?domains=${SUB}&token=${TOKEN}&ip=" >/dev/null
EOF
  chmod 755 /usr/local/bin/lazy-fitness-duckdns
  echo '*/5 * * * * root /usr/local/bin/lazy-fitness-duckdns' > /etc/cron.d/lazy-fitness-duckdns
  /usr/local/bin/lazy-fitness-duckdns || echo "DuckDNS update failed. Check the subdomain and token."
fi
cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN {
    encode gzip
    reverse_proxy 127.0.0.1:8080
}
EOF
systemctl enable caddy >/dev/null 2>&1
systemctl restart caddy
ADDRESS="https://$DOMAIN"

echo "[8/8] Checking the server"
sleep 2
if curl -fsS -m 5 http://127.0.0.1:8080/api/health >/dev/null; then HEALTH="running"; else HEALTH="NOT answering, run: sudo journalctl -u lazy-fitness -n 50"; fi

# shellcheck disable=SC1090
set -a; . "$ENVF"; set +a
cat <<EOF

=======================================================
Lazy Fitness is $HEALTH

Give testers:
  Server address : $ADDRESS
  Invite code    : $INVITE_CODE

Admin site:
  $ADDRESS/admin
  Admin token    : $ADMIN_TOKEN
(Settings live in $ENVF, readable only by root.)

Useful commands:
  sudo systemctl status lazy-fitness
  sudo journalctl -u lazy-fitness -f
  sudo nano $ENVF          then: sudo systemctl restart lazy-fitness
  sudo /usr/local/bin/lazy-fitness-backup     (backups land in $BAK)
=======================================================
EOF
