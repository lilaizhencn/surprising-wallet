-- Preserve the independent EVM block identity behind each hot-wallet balance observation.
-- Apply to an existing PostgreSQL 17+ Wallet database before deploying the anchor-aware API.
-- Older observations remain unanchored and must be refreshed before EVM routing resumes.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $$
BEGIN
    IF current_setting('server_version_num')::integer < 170000
       OR to_regclass('public.custody_hot_wallet_inventory_observation') IS NULL THEN
        RAISE EXCEPTION 'expected an existing PostgreSQL 17+ Wallet hot-wallet inventory table';
    END IF;
END $$;

LOCK TABLE public.custody_hot_wallet_inventory_observation IN ACCESS EXCLUSIVE MODE;
ALTER TABLE public.custody_hot_wallet_inventory_observation
    ADD COLUMN IF NOT EXISTS observed_block_height bigint,
    ADD COLUMN IF NOT EXISTS observed_block_hash varchar(66);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='public' AND table_name='custody_hot_wallet_inventory_observation'
                     AND column_name='observed_block_height' AND data_type='bigint')
       OR NOT EXISTS (SELECT 1 FROM information_schema.columns
                      WHERE table_schema='public' AND table_name='custody_hot_wallet_inventory_observation'
                        AND column_name='observed_block_hash' AND data_type='character varying'
                        AND character_maximum_length=66) THEN
        RAISE EXCEPTION 'hot-wallet inventory anchor columns differ from the current baseline';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid='public.custody_hot_wallet_inventory_observation'::regclass
                     AND conname='custody_hot_inventory_block_pair_check') THEN
        ALTER TABLE public.custody_hot_wallet_inventory_observation
            ADD CONSTRAINT custody_hot_inventory_block_pair_check
                CHECK ((observed_block_height IS NULL) = (observed_block_hash IS NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid='public.custody_hot_wallet_inventory_observation'::regclass
                     AND conname='custody_hot_inventory_block_height_check') THEN
        ALTER TABLE public.custody_hot_wallet_inventory_observation
            ADD CONSTRAINT custody_hot_inventory_block_height_check
                CHECK (observed_block_height IS NULL OR observed_block_height >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid='public.custody_hot_wallet_inventory_observation'::regclass
                     AND conname='custody_hot_inventory_block_hash_check') THEN
        ALTER TABLE public.custody_hot_wallet_inventory_observation
            ADD CONSTRAINT custody_hot_inventory_block_hash_check
                CHECK (observed_block_hash IS NULL OR observed_block_hash ~ '^0x[0-9a-f]{64}$');
    END IF;
END $$;
COMMIT;
