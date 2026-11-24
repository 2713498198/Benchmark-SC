#!/usr/bin/env bash
set -euo pipefail

# Back up the production MySQL database and persistent app secrets.
# Run on the server host from the repository root:
#   BACKUP_DIR=/var/backups/cryptobench ./scripts/backup-prod.sh

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROD_COMPOSE="$ROOT/scripts/prod-compose.sh"
BACKUP_DIR="${BACKUP_DIR:-$ROOT/var/backups}"
KEEP="${KEEP:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"
DEST="$BACKUP_DIR/$STAMP"

mkdir -p "$DEST"

bash "$PROD_COMPOSE" exec -T mysql \
  sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers cryptobench' \
  > "$DEST/cryptobench.sql"

# Preserve the mail encryption key; without it, queued reset/invite mail cannot
# be delivered after a restore.
bash "$PROD_COMPOSE" cp app:/app/var/secrets "$DEST/secrets" 2>/dev/null || true

tar -czf "$DEST.tar.gz" -C "$BACKUP_DIR" "$STAMP"
rm -rf "$DEST"

ls -1dt "$BACKUP_DIR"/*.tar.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f

echo "Backup written to $DEST.tar.gz"
