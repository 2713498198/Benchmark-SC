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
    # Materialize the release-built Runner archive into the host-mounted
    # directory so Docker deployments expose the ZIP in the checkout instead
    # of keeping it only inside the image.
    bundled_runner="${CRYPTOBENCH_RUNNER_BUNDLED_DIR:-/opt/cryptobench/runner-package}"
    runner_directory="${CRYPTOBENCH_RUNNER_DOWNLOAD_DIR:-/app/runner-package}"
    if [ -d "$bundled_runner" ]; then
        mkdir -p "$runner_directory"
        cp -a "$bundled_runner"/. "$runner_directory"/
        chown -R app:app "$runner_directory"
    fi
    if [ -S /var/run/docker.sock ]; then
        socket_gid="$(stat -c '%g' /var/run/docker.sock)"
        socket_group="$(awk -F: -v gid="$socket_gid" '$3 == gid { print $1; exit }' /etc/group)"
        if [ -z "$socket_group" ]; then
            socket_group=dockerhost
            groupadd -g "$socket_gid" "$socket_group"
        fi
        usermod -aG "$socket_group" app
    fi
    export HOME=/app
    export DOCKER_CONFIG=/tmp/.docker
    mkdir -p "$DOCKER_CONFIG"
    chown app:app "$DOCKER_CONFIG"
    exec setpriv --reuid="$(id -u app)" --regid="$(id -g app)" --init-groups "$@"
fi

exec "$@"
