#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
source "$TASK_ROOT/resources/scripts/regtest/local-postgres.sh"
local_pg_require
TASK_DB="surprising_wallet_test_login_upgrade_$$"
trap 'local_pg_drop "$TASK_DB"' EXIT
local_pg_create "$TASK_DB"
SQL_FILE="$TASK_ROOT/scripts/ops/upgrade-tenant-login-security.sql"
local_pg_psql "$TASK_DB" -v ON_ERROR_STOP=1 -q <<'SQL'
CREATE TABLE custody_tenant_user(id integer PRIMARY KEY, password_hash text NOT NULL);
CREATE TABLE custody_session(id integer PRIMARY KEY,tenant_user_id integer REFERENCES custody_tenant_user(id),token_hash text NOT NULL);
INSERT INTO custody_tenant_user VALUES (1,'test-only-original-hash');
INSERT INTO custody_session VALUES (1,1,'test-only-original-token-hash');
SQL
local_pg_psql "$TASK_DB" -v ON_ERROR_STOP=1 -q -f "$SQL_FILE"
local_pg_psql "$TASK_DB" -v ON_ERROR_STOP=1 -q <<'SQL'
DO $$ BEGIN
  IF (SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('custody_tenant_user','custody_session'))<>21
    OR NOT EXISTS (SELECT 1 FROM custody_tenant_user WHERE id=1 AND password_hash='test-only-original-hash' AND NOT email_verification_required AND NOT totp_required AND totp_last_step=-1 AND security_version=0)
    OR NOT EXISTS (SELECT 1 FROM custody_session WHERE id=1 AND token_hash='test-only-original-token-hash' AND verified AND NOT email_required AND NOT email_verified AND NOT totp_required AND verification_attempts=0 AND security_version=0) THEN
    RAISE EXCEPTION 'upgrade changed original data or did not create the expected defaults';
  END IF;
  BEGIN
    UPDATE custody_session SET verification_attempts=6;
    RAISE EXCEPTION 'attempt limit was not enforced';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END $$;
UPDATE custody_tenant_user SET totp_required=true,totp_ciphertext='test-only-preserved-ciphertext',totp_last_step=123,security_version=7;
UPDATE custody_session SET verified=false,email_verified=true,verification_attempts=2,security_version=7;
SQL
local_pg_psql "$TASK_DB" -v ON_ERROR_STOP=1 -q -f "$SQL_FILE"
local_pg_psql "$TASK_DB" -v ON_ERROR_STOP=1 -q <<'SQL'
DO $$ BEGIN
  IF NOT EXISTS(SELECT 1 FROM custody_tenant_user WHERE totp_required AND totp_ciphertext='test-only-preserved-ciphertext' AND totp_last_step=123 AND security_version=7)
     OR NOT EXISTS(SELECT 1 FROM custody_session WHERE NOT verified AND email_verified AND verification_attempts=2 AND security_version=7) THEN
    RAISE EXCEPTION 'repeat execution reset security state';
  END IF;
END $$;
DROP TABLE custody_session;
DROP TABLE custody_tenant_user;
CREATE TABLE custody_tenant_user(id integer PRIMARY KEY,totp_required text);
CREATE TABLE custody_session(id integer PRIMARY KEY);
SQL
if local_pg_psql "$TASK_DB" -v ON_ERROR_STOP=1 -q -f "$SQL_FILE" > /dev/null 2>&1; then
  printf 'mismatched schema unexpectedly accepted\n' >&2
  exit 1
fi
local_pg_psql "$TASK_DB" -v ON_ERROR_STOP=1 -q <<'SQL'
DO $$ BEGIN
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='custody_tenant_user' AND column_name='security_version') THEN
    RAISE EXCEPTION 'failed schema validation did not roll back added columns';
  END IF;
END $$;
SQL
printf 'PASS: additive fields, original data, defaults, attempt limit, repeat safety, mismatch rollback\n'
