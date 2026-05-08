#!/bin/sh
set -e

# Validate SYNC_INTERVAL_SECONDS
case "${SYNC_INTERVAL_SECONDS}" in
    ''|*[!0-9]*)
        echo "ERROR: SYNC_INTERVAL_SECONDS must be a positive integer, got: '${SYNC_INTERVAL_SECONDS}'"
        exit 1
        ;;
esac

if [ "${SYNC_INTERVAL_SECONDS}" -le 0 ]; then
    echo "ERROR: SYNC_INTERVAL_SECONDS must be > 0"
    exit 1
fi

echo "Starting trakt-seerr-connector, syncing every ${SYNC_INTERVAL_SECONDS}s"

while true; do
    echo "--- sync start $(date -u '+%Y-%m-%dT%H:%M:%SZ') ---"
    /app/trakt-seerr-connector sync
    rc=$?
    if [ "$rc" -eq 0 ]; then
        echo "Sync completed successfully."
    elif [ "$rc" -eq 2 ]; then
        echo "Sync completed with Seerr errors (exit 2)."
    else
        echo "Sync exited with code $rc."
    fi
    sleep "${SYNC_INTERVAL_SECONDS}"
done
