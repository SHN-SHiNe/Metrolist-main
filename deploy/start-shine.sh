#!/bin/sh
set -eu

if [ -z "${SHINE_SENDSPIN_INTERNAL_TOKEN:-}" ]; then
  SHINE_SENDSPIN_INTERNAL_TOKEN="$(cat /proc/sys/kernel/random/uuid)"
  export SHINE_SENDSPIN_INTERNAL_TOKEN
fi

/app/bin/sendspin-bridge &
bridge_pid=$!

attempt=0
until curl --fail --silent http://127.0.0.1:8936/health >/dev/null; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 50 ]; then
    echo "Sendspin bridge did not become ready" >&2
    exit 1
  fi
  sleep 0.2
done

/app/bin/shine-music-server &
server_pid=$!

shutdown() {
  kill "$server_pid" "$bridge_pid" 2>/dev/null || true
  wait "$server_pid" "$bridge_pid" 2>/dev/null || true
}

trap shutdown TERM INT EXIT

while kill -0 "$bridge_pid" 2>/dev/null && kill -0 "$server_pid" 2>/dev/null; do
  sleep 1
done

exit 1
