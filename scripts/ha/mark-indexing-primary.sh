#!/bin/sh
# Patroni passes action, role, cluster name. Run on the DB node, never through OpenProxy.
set -eu

case "${1:-}" in
    on_start|on_restart|on_role_change) ;;
    *) exit 0 ;;
esac
case "${2:-}" in
    primary|master) ;;
    *) exit 0 ;;
esac

# Use an absolute Unix socket directory and the node's PostgreSQL port.
: "${INDEXING_HA_SOCKET_DIR:?Set the local PostgreSQL Unix socket directory}"
: "${INDEXING_HA_PORT:?Set the local PostgreSQL port}"
: "${INDEXING_HA_DATABASE:?Set the actual database name (aidocs, not an OpenProxy pool)}"
: "${INDEXING_HA_USERNAME:?Set an existing account allowed to call mark_indexing_recovery_primary}"
case "$INDEXING_HA_SOCKET_DIR" in
    *,*) echo 'Use one local Unix socket directory' >&2; exit 1 ;;
    /*) ;;
    *) echo 'INDEXING_HA_SOCKET_DIR must be an absolute local Unix socket directory' >&2; exit 1 ;;
esac
case "$INDEXING_HA_PORT" in
    ''|*[!0-9]*) echo 'INDEXING_HA_PORT must be numeric' >&2; exit 1 ;;
esac
if [ "$INDEXING_HA_PORT" -lt 1 ] || [ "$INDEXING_HA_PORT" -gt 65535 ]; then
    echo 'INDEXING_HA_PORT must be between 1 and 65535' >&2
    exit 1
fi
case "$INDEXING_HA_DATABASE" in
    ''|*[!a-zA-Z0-9_]*) echo 'Use a database name, not a libpq connection string' >&2; exit 1 ;;
esac
# Inherited libpq host-address/service settings must not override local targeting.
unset PGHOSTADDR PGSERVICE PGSERVICEFILE

# Authentication is supplied by the node's pg_hba/PGPASSFILE; no credentials in arguments.
# Promotion can precede the first writable connection. Retry outside the DB transaction.
# A failed/missing callback leaves recovery disabled for this generation. Alert on exit 1.
attempt=0
while [ "$attempt" -lt 10 ]; do
    if PGCONNECT_TIMEOUT=3 psql -X --no-password -v ON_ERROR_STOP=1 \
        -h "$INDEXING_HA_SOCKET_DIR" -p "$INDEXING_HA_PORT" \
        -U "$INDEXING_HA_USERNAME" -d "$INDEXING_HA_DATABASE" \
        -c "SET statement_timeout = '5s'; DO \$\$ BEGIN
            IF pg_is_in_recovery() OR current_setting('transaction_read_only')::boolean
                OR current_setting('default_transaction_read_only')::boolean THEN
                RAISE EXCEPTION 'Local Primary is not writable yet';
            END IF;
            CALL public.mark_indexing_recovery_primary();
        END \$\$;"; then
        exit 0
    fi
    attempt=$((attempt + 1))
    if [ "$attempt" -lt 10 ]; then sleep 1; fi
done
echo 'Failed to record local writable Primary; indexing recovery remains disabled' >&2
exit 1
