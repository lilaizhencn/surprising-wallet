-- Immediately stop routing on a failed independent refresh without erasing prior evidence.
-- Apply as maintenance owner before deploying the inventory poller.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE public.custody_hot_wallet_inventory_observation
    ADD COLUMN IF NOT EXISTS invalidated_at timestamptz;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema='public' AND table_name='custody_hot_wallet_inventory_observation'
           AND column_name='invalidated_at' AND data_type='timestamp with time zone'
    ) THEN
        RAISE EXCEPTION 'hot wallet inventory invalidation column differs from baseline';
    END IF;
END $$;
COMMIT;
