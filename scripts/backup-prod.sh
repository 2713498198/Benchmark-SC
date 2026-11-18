#!/usr/bin/env bash
set -euo pipefail

# Back up the production MySQL database and persistent app secrets.
# Run on the server host from the repository root:
#   BACKUP_DIR=/var/backups/cryptobench ./scripts/backup-prod.sh

BACKUP_DIR="${BACKUP_DIR:-./var/backups}"
KEEP="${KEEP:-14}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.prod.yaml}"
STAMP="$(date +%Y%m%d-%H%M%S)"
DEST="$BACKUP_DIR/$STAMP"

mkdir -p "$DEST"

docker compose -f "$COMPOSE_FILE" exec -T mysql \
  sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers cryptobench' \
  > "$DEST/cryptobench.sql"

# Preserve the mail encryption key; without it, queued reset/invite mail cannot
# be delivered after a restore.
docker compose -f "$COMPOSE_FILE" cp app:/app/var/secrets "$DEST/secrets" 2>/dev/null || true

tar -czf "$DEST.tar.gz" -C "$BACKUP_DIR" "$STAMP"
rm -rf "$DEST"

ls -1dt "$BACKUP_DIR"/*.tar.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f

echo "Backup written to $DEST.tar.gz"
