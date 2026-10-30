#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_root"
for tool in curl tar unzip; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Missing $tool. See docs/SETUP.md for installation instructions." >&2; exit 1; }
done
source "$project_root/scripts/java-env.sh"
initialize_cryptobench_java "$project_root"
if [[ "${1:-}" == '--prepare-only' ]]; then
    set -- --version
elif [[ $# -eq 0 ]]; then
    printf 'Starting CryptoBench at http://127.0.0.1:%s\n' "${PORT:-4173}"
    set -- spring-boot:run
fi
exec sh "$project_root/mvnw" "$@"
