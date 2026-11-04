-- Apply before releasing finalized direct EVM collection accounting.
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
ALTER TABLE public.collection_record
    ADD COLUMN IF NOT EXISTS hot_inbound_accounted_at timestamptz,
    ADD COLUMN IF NOT EXISTS hot_inbound_accounted_tx_hash varchar(128),
    ADD COLUMN IF NOT EXISTS hot_inbound_receipt_block_height bigint,
    ADD COLUMN IF NOT EXISTS hot_inbound_receipt_block_hash varchar(66),
    ADD COLUMN IF NOT EXISTS hot_inbound_observed_block_height bigint,
    ADD COLUMN IF NOT EXISTS hot_inbound_observed_block_hash varchar(66);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
        WHERE conrelid='public.collection_record'::regclass
          AND conname='collection_record_hot_inbound_accounting_complete') THEN
        ALTER TABLE public.collection_record ADD CONSTRAINT collection_record_hot_inbound_accounting_complete CHECK (
            (hot_inbound_accounted_at IS NULL AND hot_inbound_accounted_tx_hash IS NULL
                AND hot_inbound_receipt_block_height IS NULL AND hot_inbound_receipt_block_hash IS NULL
                AND hot_inbound_observed_block_height IS NULL AND hot_inbound_observed_block_hash IS NULL)
            OR (hot_inbound_accounted_at IS NOT NULL AND status='CONFIRMED'
                AND tx_hash IS NOT NULL AND hot_inbound_accounted_tx_hash IS NOT NULL
                AND lower(tx_hash)=lower(hot_inbound_accounted_tx_hash)
                AND hot_inbound_receipt_block_height >= 0
                AND hot_inbound_receipt_block_hash ~ '^0x[0-9a-f]{64}$'
                AND hot_inbound_observed_block_height >= hot_inbound_receipt_block_height
                AND hot_inbound_observed_block_hash ~ '^0x[0-9a-f]{64}$')) NOT VALID;
    END IF;
END $$;
CREATE OR REPLACE FUNCTION public.wallet_enforce_collection_inbound_accounting() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF OLD.hot_inbound_accounted_at IS NOT NULL THEN
            RAISE EXCEPTION 'accounted hot collection cannot be deleted' USING ERRCODE='23514';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.hot_inbound_accounted_at IS NOT NULL AND
       (OLD.tenant_id,OLD.custody_address_id,OLD.collection_no,OLD.chain,OLD.asset_symbol,
        OLD.from_address,OLD.to_address,OLD.amount,OLD.tx_hash,OLD.status,
        OLD.hot_inbound_accounted_at,OLD.hot_inbound_accounted_tx_hash,
        OLD.hot_inbound_receipt_block_height,OLD.hot_inbound_receipt_block_hash,
        OLD.hot_inbound_observed_block_height,OLD.hot_inbound_observed_block_hash)
       IS DISTINCT FROM
       (NEW.tenant_id,NEW.custody_address_id,NEW.collection_no,NEW.chain,NEW.asset_symbol,
        NEW.from_address,NEW.to_address,NEW.amount,NEW.tx_hash,NEW.status,
        NEW.hot_inbound_accounted_at,NEW.hot_inbound_accounted_tx_hash,
        NEW.hot_inbound_receipt_block_height,NEW.hot_inbound_receipt_block_hash,
        NEW.hot_inbound_observed_block_height,NEW.hot_inbound_observed_block_hash) THEN
        RAISE EXCEPTION 'accounted hot collection facts cannot change' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger
        WHERE tgrelid='public.collection_record'::regclass
          AND tgname='collection_record_hot_inbound_accounting_immutable') THEN
        CREATE TRIGGER collection_record_hot_inbound_accounting_immutable
            BEFORE UPDATE OR DELETE ON public.collection_record FOR EACH ROW
            EXECUTE FUNCTION public.wallet_enforce_collection_inbound_accounting();
    END IF;
END $$;
COMMIT;
CREATE INDEX CONCURRENTLY IF NOT EXISTS collection_record_hot_inbound_pending_idx
    ON public.collection_record(id)
    WHERE status='CONFIRMED' AND hot_inbound_accounted_at IS NULL;
