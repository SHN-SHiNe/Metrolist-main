#!/bin/sh
set -eu

if [ -z "${SHINE_SENDSPIN_INTERNAL_TOKEN:-}" ]; then
  SHINE_SENDSPIN_INTERNAL_TOKEN="$(cat /proc/sys/kernel/random/uuid)"
  export SHINE_SENDSPIN_INTERNAL_TOKEN
fi

/app/bin/sendspin-bridge &
bridge_pid=$!

attempt=0
until curl --fail --silent --max-time 2 http://127.0.0.1:8936/health >/dev/null; do
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

bridge_failures=0
while kill -0 "$bridge_pid" 2>/dev/null && kill -0 "$server_pid" 2>/dev/null; do
  if curl --fail --silent --max-time 2 http://127.0.0.1:8936/health >/dev/null; then
    bridge_failures=0
  else
    bridge_failures=$((bridge_failures + 1))
    if [ "$bridge_failures" -ge 3 ]; then
      echo "Sendspin bridge failed three consecutive readiness checks" >&2
      exit 1
    fi
  fi
  sleep 5
done

exit 1
