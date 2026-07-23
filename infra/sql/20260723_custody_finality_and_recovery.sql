BEGIN;

SELECT pg_advisory_xact_lock(hashtext('surprising-wallet:20260723:custody-finality-recovery'));

CREATE TABLE IF NOT EXISTS public.chain_scan_block (
    chain character varying(32) NOT NULL,
    scanner_name character varying(64) NOT NULL,
    block_height bigint NOT NULL,
    block_hash character varying(128) NOT NULL,
    parent_hash character varying(128),
    observed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chain_scan_block_pkey PRIMARY KEY (chain, scanner_name, block_height)
);

CREATE INDEX IF NOT EXISTS chain_scan_block_recent_idx
    ON public.chain_scan_block (chain, scanner_name, block_height DESC);

ALTER TABLE public.deposit_record
    ADD COLUMN IF NOT EXISTS block_hash character varying(128),
    ADD COLUMN IF NOT EXISTS credit_generation integer DEFAULT 0 NOT NULL,
    ADD COLUMN IF NOT EXISTS canonical_status character varying(24) DEFAULT 'CANONICAL' NOT NULL,
    ADD COLUMN IF NOT EXISTS account_id character varying(160),
    ADD COLUMN IF NOT EXISTS reorged_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS reorg_reason text;

UPDATE public.deposit_record deposit
   SET account_id = COALESCE(
       (SELECT address.account_id
          FROM public.chain_address address
         WHERE address.tenant_id = deposit.tenant_id
           AND address.chain = deposit.chain
           AND lower(address.address) = lower(deposit.to_address)
         ORDER BY address.id
         LIMIT 1),
       lower(deposit.to_address))
 WHERE account_id IS NULL;

ALTER TABLE public.deposit_record
    ALTER COLUMN account_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.deposit_record'::regclass
           AND conname = 'deposit_record_canonical_status_check'
    ) THEN
        ALTER TABLE public.deposit_record
            ADD CONSTRAINT deposit_record_canonical_status_check
            CHECK (canonical_status IN ('CANONICAL', 'REORGED'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.deposit_record'::regclass
           AND conname = 'deposit_record_credit_generation_check'
    ) THEN
        ALTER TABLE public.deposit_record
            ADD CONSTRAINT deposit_record_credit_generation_check
            CHECK (credit_generation >= 0);
    END IF;
END
$$;

ALTER TABLE public.utxo_record
    ADD COLUMN IF NOT EXISTS block_hash character varying(128);

UPDATE public.utxo_record utxo
   SET block_hash = deposit.block_hash
  FROM public.deposit_record deposit
 WHERE utxo.block_hash IS NULL
   AND deposit.chain = utxo.chain
   AND lower(deposit.tx_hash) = lower(utxo.tx_hash)
   AND deposit.log_index = utxo.vout
   AND deposit.block_hash IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.utxo_record WHERE block_hash IS NULL) THEN
        RAISE EXCEPTION
            'utxo_record has rows without canonical block hashes; backfill them from the chain before deployment';
    END IF;
END
$$;

ALTER TABLE public.utxo_record
    ALTER COLUMN block_hash SET NOT NULL;

CREATE TABLE IF NOT EXISTS public.custody_asset_recovery (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    custody_address_id uuid,
    actual_chain character varying(32) NOT NULL,
    expected_chain character varying(32),
    asset_symbol character varying(32) NOT NULL,
    token_contract character varying(128),
    token_decimals integer,
    tx_hash character varying(128) NOT NULL,
    log_index bigint DEFAULT 0 NOT NULL,
    requested_log_index bigint,
    destination_address character varying(160) NOT NULL,
    recovery_address character varying(160),
    claimed_amount numeric(78,24),
    verified_amount numeric(78,24),
    block_height bigint,
    block_hash character varying(128),
    confirmations integer DEFAULT 0 NOT NULL,
    status character varying(32) DEFAULT 'SUBMITTED' NOT NULL,
    verification_details jsonb DEFAULT '{}'::jsonb NOT NULL,
    failure_reason text,
    recovery_tx_hash character varying(128),
    requested_by uuid NOT NULL,
    reviewed_by uuid,
    executed_by uuid,
    approved_at timestamp with time zone,
    executed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_asset_recovery_pkey PRIMARY KEY (id),
    CONSTRAINT custody_asset_recovery_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT custody_asset_recovery_business_key UNIQUE (actual_chain, tx_hash, log_index),
    CONSTRAINT custody_asset_recovery_status_check CHECK (
        status IN ('SUBMITTED', 'VERIFIED', 'APPROVED', 'EXECUTING', 'BROADCAST',
                   'RECOVERED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT custody_asset_recovery_decimals_check
        CHECK (token_decimals IS NULL OR token_decimals BETWEEN 0 AND 36),
    CONSTRAINT custody_asset_recovery_requested_log_index_check
        CHECK (requested_log_index IS NULL OR requested_log_index >= 0),
    CONSTRAINT custody_asset_recovery_tenant_id_fkey FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT custody_asset_recovery_address_fk FOREIGN KEY (tenant_id, custody_address_id)
        REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS custody_asset_recovery_tenant_status_idx
    ON public.custody_asset_recovery (tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS custody_asset_recovery_platform_status_idx
    ON public.custody_asset_recovery (status, created_at DESC);

CREATE TABLE IF NOT EXISTS public.custody_reorg_deficit (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    custody_address_id uuid NOT NULL,
    deposit_record_id bigint NOT NULL,
    chain character varying(32) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    account_id character varying(160) NOT NULL,
    original_amount numeric(78,24) NOT NULL,
    deficit_amount numeric(78,24) NOT NULL,
    recovered_amount numeric(78,24) DEFAULT 0 NOT NULL,
    status character varying(24) DEFAULT 'OPEN' NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT custody_reorg_deficit_pkey PRIMARY KEY (id),
    CONSTRAINT custody_reorg_deficit_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT custody_reorg_deficit_deposit_key UNIQUE (tenant_id, deposit_record_id),
    CONSTRAINT custody_reorg_deficit_status_check CHECK (status IN ('OPEN', 'RECOVERED')),
    CONSTRAINT custody_reorg_deficit_amount_check CHECK (
        deficit_amount > 0 AND recovered_amount >= 0 AND recovered_amount <= deficit_amount),
    CONSTRAINT custody_reorg_deficit_tenant_id_fkey FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT custody_reorg_deficit_address_fk FOREIGN KEY (tenant_id, custody_address_id)
        REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT custody_reorg_deficit_deposit_record_fkey FOREIGN KEY (tenant_id, deposit_record_id)
        REFERENCES public.deposit_record(tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS custody_reorg_deficit_account_idx
    ON public.custody_reorg_deficit (tenant_id, chain, asset_symbol, account_id, status);

COMMIT;
