#!/bin/bash
# Starts the Lazy Fitness server for a small test (about 5 people) and, if cloudflared is installed,
# opens a free public HTTPS address for it. Keep this window open while people test.
cd "$(dirname "$0")"
export PATH="$HOME/.local/bin:$PATH"
[ -f .invite_code ] || python3 -c 'import secrets;print(secrets.token_hex(3))' > .invite_code
export INVITE_CODE="${INVITE_CODE:-$(cat .invite_code)}"
export MAX_USERS="${MAX_USERS:-10}"
echo "Invite code to give testers: $INVITE_CODE"

if command -v cloudflared >/dev/null 2>&1; then
  cloudflared tunnel --url http://localhost:${PORT:-8080} > .tunnel.log 2>&1 &
  for _ in $(seq 1 30); do
    URL=$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' .tunnel.log | head -1)
    [ -n "$URL" ] && break; sleep 1
  done
  echo "Server address to give testers: ${URL:-not found, see .tunnel.log}"
else
  echo "cloudflared not installed, so the server is only reachable on this network (see README)."
fi
trap 'kill $(jobs -p) 2>/dev/null' EXIT
caffeinate -i python3 server.py
