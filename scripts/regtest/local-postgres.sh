#!/usr/bin/env bash

# Shared PostgreSQL 18 instance for local integration tests. This helper never
# starts a database server; it only creates isolated databases inside the
# developer's existing local instance.

REGTEST_PG_HOST=${REGTEST_PG_HOST:-127.0.0.1}
REGTEST_PG_PORT=${REGTEST_PG_PORT:-5432}
REGTEST_PG_USER=${REGTEST_PG_USER:-$(id -un)}
REGTEST_PG_PASSWORD=${REGTEST_PG_PASSWORD:-}
REGTEST_PG_ADMIN_DB=${REGTEST_PG_ADMIN_DB:-postgres}

local_pg_psql() {
  local database=$1
  shift
  PGPASSWORD="$REGTEST_PG_PASSWORD" psql \
    -h "$REGTEST_PG_HOST" -p "$REGTEST_PG_PORT" \
    -U "$REGTEST_PG_USER" -d "$database" "$@"
}

local_pg_require() {
  case "$REGTEST_PG_HOST" in
    127.0.0.1|localhost|::1) ;;
    *)
      printf 'regtest PostgreSQL must be local, got host: %s\n' "$REGTEST_PG_HOST" >&2
      return 1
      ;;
  esac

  for command in psql createdb dropdb; do
    command -v "$command" >/dev/null || {
      printf 'missing required PostgreSQL command: %s\n' "$command" >&2
      return 1
    }
  done

  local version_num
  version_num=$(local_pg_psql "$REGTEST_PG_ADMIN_DB" -Atqc \
    "select current_setting('server_version_num')")
  if [[ $((version_num / 10000)) != 18 ]]; then
    printf 'regtest requires the installed local PostgreSQL 18, got version_num=%s\n' \
      "$version_num" >&2
    return 1
  fi
}

local_pg_validate_test_database() {
  local database=$1
  if [[ ! "$database" =~ ^surprising_wallet_test_[a-z0-9_]+$ ]]; then
    printf 'refusing unsafe test database name: %s\n' "$database" >&2
    return 1
  fi
}

local_pg_create() {
  local database=$1
  local_pg_validate_test_database "$database"
  PGPASSWORD="$REGTEST_PG_PASSWORD" dropdb --if-exists --force \
    -h "$REGTEST_PG_HOST" -p "$REGTEST_PG_PORT" \
    -U "$REGTEST_PG_USER" "$database"
  PGPASSWORD="$REGTEST_PG_PASSWORD" createdb \
    -h "$REGTEST_PG_HOST" -p "$REGTEST_PG_PORT" \
    -U "$REGTEST_PG_USER" "$database"
}

local_pg_drop() {
  local database=$1
  local_pg_validate_test_database "$database"
  PGPASSWORD="$REGTEST_PG_PASSWORD" dropdb --if-exists --force \
    -h "$REGTEST_PG_HOST" -p "$REGTEST_PG_PORT" \
    -U "$REGTEST_PG_USER" "$database"
}

local_pg_jdbc_url() {
  local database=$1
  local_pg_validate_test_database "$database"
  printf 'jdbc:postgresql://%s:%s/%s' "$REGTEST_PG_HOST" "$REGTEST_PG_PORT" "$database"
}

local_pg_uri() {
  local database=$1
  local_pg_validate_test_database "$database"
  if [[ ! "$REGTEST_PG_USER" =~ ^[A-Za-z0-9._-]+$ \
        || ! "$REGTEST_PG_PASSWORD" =~ ^[A-Za-z0-9._-]*$ ]]; then
    printf 'REGTEST_PG_USER and REGTEST_PG_PASSWORD must be URL-safe for this flow\n' >&2
    return 1
  fi
  if [[ -n "$REGTEST_PG_PASSWORD" ]]; then
    printf 'postgres://%s:%s@%s:%s/%s' "$REGTEST_PG_USER" "$REGTEST_PG_PASSWORD" \
      "$REGTEST_PG_HOST" "$REGTEST_PG_PORT" "$database"
  else
    printf 'postgres://%s@%s:%s/%s' "$REGTEST_PG_USER" \
      "$REGTEST_PG_HOST" "$REGTEST_PG_PORT" "$database"
  fi
}
