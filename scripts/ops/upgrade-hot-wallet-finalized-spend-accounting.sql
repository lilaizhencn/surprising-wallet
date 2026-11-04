-- Add one-way finalized-spend accounting evidence to an existing Wallet database.
-- Apply as maintenance owner before deploying reconciliation code; no existing reservation is released.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
DO $$
BEGIN
    IF current_setting('server_version_num')::integer < 170000
       OR to_regclass('public.custody_hot_wallet_withdrawal_reservation') IS NULL THEN
        RAISE EXCEPTION 'expected an existing PostgreSQL 17+ Wallet reservation table';
    END IF;
END $$;
LOCK TABLE public.custody_hot_wallet_withdrawal_reservation, public.withdrawal_order
    IN ACCESS EXCLUSIVE MODE;
ALTER TABLE public.custody_hot_wallet_withdrawal_reservation
    ADD COLUMN IF NOT EXISTS accounted_at timestamptz,
    ADD COLUMN IF NOT EXISTS accounted_tx_hash varchar(66),
    ADD COLUMN IF NOT EXISTS accounted_receipt_block_height bigint,
    ADD COLUMN IF NOT EXISTS accounted_receipt_block_hash varchar(66),
    ADD COLUMN IF NOT EXISTS accounted_observed_block_height bigint,
    ADD COLUMN IF NOT EXISTS accounted_observed_block_hash varchar(66);
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM (VALUES
            ('accounted_at','timestamp with time zone',NULL::integer),
            ('accounted_tx_hash','character varying',66),
            ('accounted_receipt_block_height','bigint',NULL::integer),
            ('accounted_receipt_block_hash','character varying',66),
            ('accounted_observed_block_height','bigint',NULL::integer),
            ('accounted_observed_block_hash','character varying',66)
        ) expected(name,data_type,max_length)
        LEFT JOIN information_schema.columns actual
          ON actual.table_schema='public'
         AND actual.table_name='custody_hot_wallet_withdrawal_reservation'
         AND actual.column_name=expected.name
        WHERE actual.column_name IS NULL OR actual.data_type<>expected.data_type
           OR (expected.max_length IS NOT NULL AND actual.character_maximum_length<>expected.max_length)
    ) THEN
        RAISE EXCEPTION 'hot wallet finalized-spend accounting columns differ from baseline';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid='public.custody_hot_wallet_withdrawal_reservation'::regclass
                     AND conname='custody_hot_reservation_accounted_check') THEN
        ALTER TABLE public.custody_hot_wallet_withdrawal_reservation
            ADD CONSTRAINT custody_hot_reservation_accounted_check CHECK (
                (accounted_at IS NULL AND accounted_tx_hash IS NULL
                    AND accounted_receipt_block_height IS NULL AND accounted_receipt_block_hash IS NULL
                    AND accounted_observed_block_height IS NULL AND accounted_observed_block_hash IS NULL)
                OR (status='CONFIRMED' AND terminal_at IS NOT NULL AND accounted_at IS NOT NULL
                    AND accounted_at>=terminal_at
                    AND accounted_tx_hash IS NOT NULL AND accounted_tx_hash ~ '^0x[0-9a-f]{64}$'
                    AND accounted_receipt_block_height IS NOT NULL AND accounted_receipt_block_height>=0
                    AND accounted_receipt_block_hash IS NOT NULL
                    AND accounted_receipt_block_hash ~ '^0x[0-9a-f]{64}$'
                    AND accounted_observed_block_height IS NOT NULL
                    AND accounted_observed_block_height>=accounted_receipt_block_height
                    AND accounted_observed_block_hash IS NOT NULL
                    AND accounted_observed_block_hash ~ '^0x[0-9a-f]{64}$')
            );
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS custody_hot_wallet_withdrawal_reservation_unaccounted_idx
    ON public.custody_hot_wallet_withdrawal_reservation(id)
    WHERE status = 'CONFIRMED' AND accounted_at IS NULL;
CREATE OR REPLACE FUNCTION public.custody_enforce_hot_wallet_reservation() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'hot wallet withdrawal reservation cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF (NEW.id,NEW.tenant_id,NEW.wallet_id,NEW.chain,NEW.asset_symbol,NEW.order_no,NEW.source_address,
        NEW.amount,NEW.source_observed_at,NEW.created_at) IS DISTINCT FROM
       (OLD.id,OLD.tenant_id,OLD.wallet_id,OLD.chain,OLD.asset_symbol,OLD.order_no,OLD.source_address,
        OLD.amount,OLD.source_observed_at,OLD.created_at) THEN
        RAISE EXCEPTION 'hot wallet withdrawal reservation identity or terminal state cannot change' USING ERRCODE='23514';
    END IF;
    IF OLD.status='OPEN' AND NEW.status IN ('CONFIRMED','FAILED','CANCELLED')
       AND NEW.terminal_at IS NOT NULL AND NEW.accounted_at IS NULL THEN
        RETURN NEW;
    END IF;
    IF OLD.status='CONFIRMED' AND OLD.accounted_at IS NULL
       AND NEW.status='CONFIRMED' AND NEW.terminal_at=OLD.terminal_at
       AND NEW.accounted_at IS NOT NULL THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'hot wallet withdrawal reservation terminal or accounted state cannot change'
        USING ERRCODE='23514';
END;
$$;
CREATE OR REPLACE FUNCTION public.custody_guard_hot_wallet_withdrawal_order() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.tenant_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM public.custody_hot_wallet_withdrawal_reservation
         WHERE tenant_id=OLD.tenant_id AND chain=OLD.chain AND order_no=OLD.order_no
    ) AND (
        (NEW.id,NEW.tenant_id,NEW.order_no,NEW.user_id,NEW.chain,NEW.asset_symbol,
         NEW.from_address,NEW.to_address,NEW.amount,NEW.debit_account_id) IS DISTINCT FROM
        (OLD.id,OLD.tenant_id,OLD.order_no,OLD.user_id,OLD.chain,OLD.asset_symbol,
         OLD.from_address,OLD.to_address,OLD.amount,OLD.debit_account_id)
        OR (OLD.tx_hash IS NOT NULL AND NEW.tx_hash IS DISTINCT FROM OLD.tx_hash)
    ) THEN
        RAISE EXCEPTION 'reserved hot wallet withdrawal intent or bound hash cannot change'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS custody_hot_wallet_withdrawal_order_guard ON public.withdrawal_order;
CREATE TRIGGER custody_hot_wallet_withdrawal_order_guard
    BEFORE UPDATE ON public.withdrawal_order
    FOR EACH ROW EXECUTE FUNCTION public.custody_guard_hot_wallet_withdrawal_order();
COMMIT;
