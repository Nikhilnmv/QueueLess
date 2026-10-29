#!/usr/bin/env bash
# Runs a project-local PostgreSQL cluster for development and integration tests
# without Docker. Data lives in .local/pgdata (git-ignored).
#
# Usage: scripts/local-postgres.sh [start|stop|status|reset-dev|reset-test|psql]
#
# Requires PostgreSQL server binaries (initdb, pg_ctl, psql) on PATH, e.g.
# `brew install postgresql@17` on macOS or `apt install postgresql` on Linux.
# Docker users can use `docker compose up -d db` instead (same port and credentials).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_DIR="$ROOT/.local"
PGDATA="$LOCAL_DIR/pgdata"
PGPORT="${QUEUELESS_PG_PORT:-5433}"
LOG_FILE="$LOCAL_DIR/postgres.log"
APP_USER="queueless"
APP_PASSWORD="queueless"   # local development only, never used in production
SUPERUSER="postgres"

export PGPASSWORD="$SUPERUSER"
# macOS: the postmaster refuses to start ("became multithreaded during startup")
# unless it runs with a valid locale; the C locale is always available.
export LC_ALL=C

psql_admin() {
  psql -h localhost -p "$PGPORT" -U "$SUPERUSER" -d postgres -v ON_ERROR_STOP=1 -qtA "$@"
}

ensure_database() {
  local db="$1"
  if [ "$(psql_admin -c "SELECT 1 FROM pg_database WHERE datname = '$db'")" != "1" ]; then
    psql_admin -c "CREATE DATABASE $db OWNER $APP_USER"
    echo "Created database $db"
  fi
}

start() {
  mkdir -p "$LOCAL_DIR"
  if [ ! -f "$PGDATA/PG_VERSION" ]; then
    echo "Initialising PostgreSQL cluster in $PGDATA"
    local pwfile
    pwfile="$(mktemp)"
    printf '%s\n' "$SUPERUSER" > "$pwfile"
    initdb -D "$PGDATA" -U "$SUPERUSER" --pwfile="$pwfile" \
      --auth-local=scram-sha-256 --auth-host=scram-sha-256 \
      --encoding=UTF8 --locale=C >/dev/null
    rm -f "$pwfile"
  fi

  if pg_ctl -D "$PGDATA" status >/dev/null 2>&1; then
    echo "PostgreSQL already running on port $PGPORT"
  else
    pg_ctl -D "$PGDATA" -l "$LOG_FILE" -w \
      -o "-p $PGPORT -c listen_addresses=localhost -c unix_socket_directories='$LOCAL_DIR' -c max_connections=150" \
      start >/dev/null
    echo "PostgreSQL started on localhost:$PGPORT (log: $LOG_FILE)"
  fi

  if [ "$(psql_admin -c "SELECT 1 FROM pg_roles WHERE rolname = '$APP_USER'")" != "1" ]; then
    psql_admin -c "CREATE ROLE $APP_USER LOGIN PASSWORD '$APP_PASSWORD'"
    echo "Created role $APP_USER"
  fi
  ensure_database queueless
  ensure_database queueless_test
}

stop() {
  if pg_ctl -D "$PGDATA" status >/dev/null 2>&1; then
    pg_ctl -D "$PGDATA" -m fast -w stop >/dev/null
    echo "PostgreSQL stopped"
  else
    echo "PostgreSQL is not running"
  fi
}

reset_db() {
  local db="$1"
  psql_admin -c "DROP DATABASE IF EXISTS $db WITH (FORCE)"
  psql_admin -c "CREATE DATABASE $db OWNER $APP_USER"
  echo "Database $db reset (Flyway recreates the schema on next start)"
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  status) pg_ctl -D "$PGDATA" status || true ;;
  reset-dev) reset_db queueless ;;
  reset-test) reset_db queueless_test ;;
  psql) PGPASSWORD="$APP_PASSWORD" exec psql -h localhost -p "$PGPORT" -U "$APP_USER" -d "${2:-queueless}" ;;
  *) echo "Usage: $0 [start|stop|status|reset-dev|reset-test|psql [db]]" >&2; exit 2 ;;
esac
