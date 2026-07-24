-- ============================================================
-- Migration: Add missing tables and columns
-- Generated 2026-07-23
-- ============================================================

-- 1. Create evm_7702_config table
-- ============================================================
CREATE TABLE IF NOT EXISTS public.evm_7702_config (
    id uuid NOT NULL PRIMARY KEY,
    chain character varying(32) NOT NULL,
    network character varying(64) NOT NULL,
    chain_id numeric(78,0) NOT NULL,
    version integer NOT NULL,
    delegate_address character varying(42) NOT NULL,
    delegate_code_hash character varying(66) NOT NULL,
    collector_address character varying(42) NOT NULL,
    collector_code_hash character varying(66) NOT NULL,
    payout_delegate_address character varying(42) NOT NULL,
    payout_delegate_code_hash character varying(66) NOT NULL,
    relayer_chain_address_id bigint NOT NULL,
    relayer_address character varying(42) NOT NULL,
    status character varying(24) DEFAULT 'DISABLED'::character varying NOT NULL,
    max_batch_items integer DEFAULT 20 NOT NULL,
    max_batch_gas bigint DEFAULT 5000000 NOT NULL,
    block_gas_ratio numeric(5,4) DEFAULT 0.3000 NOT NULL,
    gas_limit_multiplier numeric(5,4) DEFAULT 1.2000 NOT NULL,
    signature_ttl_seconds integer DEFAULT 900 NOT NULL,
    required_confirmations integer DEFAULT 2 NOT NULL,
    native_collection_enabled boolean DEFAULT false NOT NULL,
    batch_withdrawal_enabled boolean DEFAULT false NOT NULL,
    withdrawal_max_wait_ms integer DEFAULT 3000 NOT NULL,
    withdrawal_max_batch_items integer DEFAULT 20 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evm_7702_config_status_check CHECK (status = ANY (ARRAY['DISABLED', 'SHADOW', 'ACTIVE', 'PAUSED'])),
    CONSTRAINT evm_7702_config_chain_id_check CHECK (chain_id > 0),
    CONSTRAINT evm_7702_config_version_check CHECK (version > 0),
    CONSTRAINT evm_7702_config_batch_check CHECK (max_batch_items BETWEEN 1 AND 100),
    CONSTRAINT evm_7702_config_gas_check CHECK (max_batch_gas > 0),
    CONSTRAINT evm_7702_config_block_ratio_check CHECK (block_gas_ratio > 0 AND block_gas_ratio <= 0.5),
    CONSTRAINT evm_7702_config_multiplier_check CHECK (gas_limit_multiplier >= 1.0 AND gas_limit_multiplier <= 2.0),
    CONSTRAINT evm_7702_config_ttl_check CHECK (signature_ttl_seconds BETWEEN 30 AND 1800),
    CONSTRAINT evm_7702_config_confirmations_check CHECK (required_confirmations > 0),
    CONSTRAINT evm_7702_config_withdraw_wait_check CHECK (withdrawal_max_wait_ms BETWEEN 0 AND 60000),
    CONSTRAINT evm_7702_config_withdraw_batch_check CHECK (withdrawal_max_batch_items BETWEEN 1 AND 100),
    CONSTRAINT evm_7702_config_network_version_key UNIQUE (chain, network, version),
    CONSTRAINT evm_7702_config_relayer_fk FOREIGN KEY (relayer_chain_address_id)
        REFERENCES public.chain_address(id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX IF NOT EXISTS evm_7702_config_one_active_version
    ON public.evm_7702_config USING btree (chain, network)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX IF NOT EXISTS evm_7702_config_one_shadow_version
    ON public.evm_7702_config USING btree (chain, network)
    WHERE status = 'SHADOW';


-- 2. Create custody_asset_recovery table
-- ============================================================
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


-- 3. Add missing columns to custody_gas_usage
-- ============================================================
ALTER TABLE public.custody_gas_usage
    ADD COLUMN IF NOT EXISTS operation_type character varying(32),
    ADD COLUMN IF NOT EXISTS operation_id uuid,
    ADD COLUMN IF NOT EXISTS reference_no character varying(96);

-- Set defaults for existing rows
UPDATE public.custody_gas_usage
   SET operation_type = 'WITHDRAWAL',
       operation_id = COALESCE(operation_id, custody_withdrawal_id),
       reference_no = COALESCE(reference_no, order_no)
 WHERE operation_type IS NULL;

ALTER TABLE public.custody_gas_usage
    ALTER COLUMN operation_type SET NOT NULL,
    ALTER COLUMN operation_id SET NOT NULL,
    ALTER COLUMN reference_no SET NOT NULL;

-- Add operation_type check constraint
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.custody_gas_usage'::regclass
           AND conname = 'custody_gas_usage_operation_type_check'
    ) THEN
        ALTER TABLE public.custody_gas_usage
            ADD CONSTRAINT custody_gas_usage_operation_type_check
            CHECK (operation_type IN ('WITHDRAWAL', 'COLLECTION_BATCH', 'WITHDRAWAL_BATCH'));
    END IF;
END;
$$;

-- Add unique constraint for tenant_id + operation_type + operation_id
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.custody_gas_usage'::regclass
           AND conname = 'custody_gas_usage_operation_key'
    ) THEN
        ALTER TABLE public.custody_gas_usage
            ADD CONSTRAINT custody_gas_usage_operation_key UNIQUE (tenant_id, operation_type, operation_id);
    END IF;
END;
$$;


-- 4. Add missing columns to deposit_record
-- ============================================================
ALTER TABLE public.deposit_record
    ADD COLUMN IF NOT EXISTS block_hash character varying(128),
    ADD COLUMN IF NOT EXISTS credit_generation integer DEFAULT 0 NOT NULL,
    ADD COLUMN IF NOT EXISTS canonical_status character varying(24) DEFAULT 'CANONICAL' NOT NULL,
    ADD COLUMN IF NOT EXISTS account_id character varying(160),
    ADD COLUMN IF NOT EXISTS reorged_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS reorg_reason text;

-- Backfill account_id for existing rows
UPDATE public.deposit_record deposit
   SET account_id = COALESCE(
       (SELECT address.account_id
          FROM public.chain_address address
         WHERE address.chain = deposit.chain
           AND lower(address.address) = lower(deposit.to_address)
         ORDER BY address.id
         LIMIT 1),
       lower(deposit.to_address))
 WHERE account_id IS NULL;

ALTER TABLE public.deposit_record
    ALTER COLUMN account_id SET NOT NULL;

-- Add canonical_status check constraint
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
END;
$$;
