-- Add the tenant login security fields to an EXISTING Wallet database.
-- Run as its maintenance owner, after checking the target and taking a backup.
-- This changes only public.custody_tenant_user and public.custody_session.
-- Keep these columns when rolling back application binaries; never drop enrolled secrets.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $$
BEGIN
    IF current_setting('server_version_num')::integer < 170000
       OR to_regclass('public.custody_tenant_user') IS NULL
       OR to_regclass('public.custody_session') IS NULL THEN
        RAISE EXCEPTION 'expected an existing PostgreSQL 17+ Wallet database';
    END IF;
END $$;

LOCK TABLE public.custody_tenant_user, public.custody_session IN ACCESS EXCLUSIVE MODE;

ALTER TABLE public.custody_tenant_user
    ADD COLUMN IF NOT EXISTS email_verification_required boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS totp_required boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS totp_ciphertext text,
    ADD COLUMN IF NOT EXISTS totp_last_step bigint NOT NULL DEFAULT -1,
    ADD COLUMN IF NOT EXISTS security_version bigint NOT NULL DEFAULT 0;

ALTER TABLE public.custody_session
    ADD COLUMN IF NOT EXISTS security_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS verified boolean NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS email_required boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS email_verified boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS totp_required boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS verification_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS email_code_hash varchar(64),
    ADD COLUMN IF NOT EXISTS email_code_expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS email_sent_at timestamptz,
    ADD COLUMN IF NOT EXISTS enrollment_ciphertext text,
    ADD COLUMN IF NOT EXISTS enrollment_expires_at timestamptz;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid='public.custody_session'::regclass
                     AND conname='custody_session_verification_attempts_check') THEN
        ALTER TABLE public.custody_session
            ADD CONSTRAINT custody_session_verification_attempts_check
                CHECK (verification_attempts BETWEEN 0 AND 5);
    END IF;
    IF EXISTS (
        SELECT 1
          FROM (VALUES
            ('custody_tenant_user','email_verification_required','boolean','NO'),
            ('custody_tenant_user','totp_required','boolean','NO'),
            ('custody_tenant_user','totp_ciphertext','text','YES'),
            ('custody_tenant_user','totp_last_step','bigint','NO'),
            ('custody_tenant_user','security_version','bigint','NO'),
            ('custody_session','security_version','bigint','NO'),
            ('custody_session','verified','boolean','NO'),
            ('custody_session','email_required','boolean','NO'),
            ('custody_session','email_verified','boolean','NO'),
            ('custody_session','totp_required','boolean','NO'),
            ('custody_session','verification_attempts','integer','NO'),
            ('custody_session','email_code_hash','character varying','YES'),
            ('custody_session','email_code_expires_at','timestamp with time zone','YES'),
            ('custody_session','email_sent_at','timestamp with time zone','YES'),
            ('custody_session','enrollment_ciphertext','text','YES'),
            ('custody_session','enrollment_expires_at','timestamp with time zone','YES')
          ) expected(table_name,column_name,data_type,is_nullable)
          LEFT JOIN information_schema.columns actual
            ON actual.table_schema='public' AND actual.table_name=expected.table_name
           AND actual.column_name=expected.column_name
         WHERE actual.column_name IS NULL OR actual.data_type<>expected.data_type
            OR actual.is_nullable<>expected.is_nullable
            OR (actual.column_name='email_code_hash' AND actual.character_maximum_length<>64)
    ) THEN
        RAISE EXCEPTION 'login security schema differs from the current baseline';
    END IF;
END $$;
COMMIT;
