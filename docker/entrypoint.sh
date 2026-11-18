#!/bin/sh
set -eu

# Bind-mounted /app/var may be owned by the host user. Fix ownership once,
# then drop root before starting Java. Named volumes inherit app ownership and
# skip the recursive walk after the marker exists.
if [ "$(id -u)" = "0" ]; then
    mkdir -p /app/var
    if [ ! -f /app/var/.cryptobench-owner-v1 ]; then
        chown -R app:app /app/var
        touch /app/var/.cryptobench-owner-v1
        chown app:app /app/var/.cryptobench-owner-v1
    fi
    exec setpriv --reuid="$(id -u app)" --regid="$(id -g app)" --init-groups "$@"
fi

exec "$@"
