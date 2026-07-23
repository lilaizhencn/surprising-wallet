BEGIN;

SELECT pg_advisory_xact_lock(hashtext('surprising-wallet:20260723:eip7702-native-batch'));

ALTER TABLE public.evm_7702_config
    ADD COLUMN IF NOT EXISTS payout_delegate_address character varying(42),
    ADD COLUMN IF NOT EXISTS payout_delegate_code_hash character varying(66),
    ADD COLUMN IF NOT EXISTS native_collection_enabled boolean DEFAULT false NOT NULL,
    ADD COLUMN IF NOT EXISTS batch_withdrawal_enabled boolean DEFAULT false NOT NULL,
    ADD COLUMN IF NOT EXISTS withdrawal_max_wait_ms integer DEFAULT 3000 NOT NULL,
    ADD COLUMN IF NOT EXISTS withdrawal_max_batch_items integer DEFAULT 20 NOT NULL;

-- Existing configurations remain non-active and use the collection delegate as a
-- safe placeholder until an operator deploys and verifies the payout delegate.
UPDATE public.evm_7702_config
   SET payout_delegate_address = delegate_address,
       payout_delegate_code_hash = delegate_code_hash
 WHERE payout_delegate_address IS NULL
    OR payout_delegate_code_hash IS NULL;

ALTER TABLE public.evm_7702_config
    ALTER COLUMN payout_delegate_address SET NOT NULL,
    ALTER COLUMN payout_delegate_code_hash SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.evm_7702_config'::regclass
           AND conname = 'evm_7702_config_withdraw_wait_check'
    ) THEN
        ALTER TABLE public.evm_7702_config
            ADD CONSTRAINT evm_7702_config_withdraw_wait_check
            CHECK (withdrawal_max_wait_ms BETWEEN 0 AND 60000);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.evm_7702_config'::regclass
           AND conname = 'evm_7702_config_withdraw_batch_check'
    ) THEN
        ALTER TABLE public.evm_7702_config
            ADD CONSTRAINT evm_7702_config_withdraw_batch_check
            CHECK (withdrawal_max_batch_items BETWEEN 1 AND 100);
    END IF;
END
$$;

ALTER TABLE public.custody_gas_usage
    DROP CONSTRAINT IF EXISTS custody_gas_usage_operation_type_check;
ALTER TABLE public.custody_gas_usage
    ADD CONSTRAINT custody_gas_usage_operation_type_check
    CHECK (operation_type IN ('WITHDRAWAL', 'COLLECTION_BATCH', 'WITHDRAWAL_BATCH'));

CREATE TABLE IF NOT EXISTS public.evm_7702_payout_account (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    chain character varying(32) NOT NULL,
    network character varying(64) NOT NULL,
    chain_address_id bigint NOT NULL,
    authority_address character varying(42) NOT NULL,
    delegate_address character varying(42),
    delegate_version integer,
    delegation_status character varying(24) DEFAULT 'NOT_DELEGATED' NOT NULL,
    observed_authority_nonce numeric(78,0),
    observed_operation_nonce numeric(78,0),
    activation_tx_hash character varying(128),
    revocation_tx_hash character varying(128),
    last_code_hash character varying(66),
    last_observed_block bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evm_7702_payout_account_tenant_chain_key UNIQUE (tenant_id, chain),
    CONSTRAINT evm_7702_payout_account_tenant_authority_key
        UNIQUE (tenant_id, chain, authority_address),
    CONSTRAINT evm_7702_payout_account_status_check CHECK (
        delegation_status IN ('NOT_DELEGATED', 'DELEGATING', 'ACTIVE', 'REVOKING',
                              'REVOKED', 'UNKNOWN', 'MANUAL_REVIEW')),
    CONSTRAINT evm_7702_payout_account_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT evm_7702_payout_account_address_fk FOREIGN KEY (tenant_id, chain_address_id)
        REFERENCES public.chain_address(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS public.evm_withdrawal_batch (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    chain character varying(32) NOT NULL,
    network character varying(64) NOT NULL,
    asset_symbol character varying(32) NOT NULL,
    token_contract character varying(42) NOT NULL,
    token_decimals integer NOT NULL,
    hot_wallet character varying(42) NOT NULL,
    relayer_address character varying(42) NOT NULL,
    delegate_version integer NOT NULL,
    batch_hash character varying(66) NOT NULL,
    status character varying(32) NOT NULL,
    item_count integer NOT NULL,
    authorization_included boolean DEFAULT false NOT NULL,
    authorization_nonce numeric(78,0),
    operation_nonce numeric(78,0),
    signature_deadline timestamp with time zone,
    estimated_gas bigint,
    gas_limit bigint,
    max_fee_per_gas numeric(78,0),
    max_priority_fee_per_gas numeric(78,0),
    canonical_tx_hash character varying(128),
    actual_gas_used bigint,
    effective_gas_price numeric(78,0),
    l2_fee_atomic numeric(78,0),
    l1_fee_atomic numeric(78,0),
    operator_fee_atomic numeric(78,0),
    total_fee_atomic numeric(78,0),
    actual_fee numeric(78,24),
    confirmed_block_number bigint,
    confirmed_block_hash character varying(128),
    error_code character varying(64),
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    submitted_at timestamp with time zone,
    confirmed_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evm_withdrawal_batch_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT evm_withdrawal_batch_hash_key UNIQUE (chain, batch_hash),
    CONSTRAINT evm_withdrawal_batch_item_count_check CHECK (item_count BETWEEN 1 AND 100),
    CONSTRAINT evm_withdrawal_batch_status_check CHECK (
        status IN ('LOCKED', 'SIGNING', 'SUBMITTED', 'BROADCAST_UNKNOWN', 'CONFIRMING',
                   'CONFIRMED', 'PARTIAL_FAILED', 'FAILED', 'REORGED', 'MANUAL_REVIEW')),
    CONSTRAINT evm_withdrawal_batch_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX IF NOT EXISTS evm_withdrawal_batch_tx_key
    ON public.evm_withdrawal_batch (chain, canonical_tx_hash)
    WHERE canonical_tx_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS evm_withdrawal_batch_work_idx
    ON public.evm_withdrawal_batch (chain, network, status, updated_at);

CREATE TABLE IF NOT EXISTS public.evm_withdrawal_batch_item (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    item_index integer NOT NULL,
    withdrawal_order_id bigint NOT NULL,
    custody_withdrawal_id uuid NOT NULL,
    withdrawal_id_hash character varying(66) NOT NULL,
    recipient character varying(42) NOT NULL,
    token_contract character varying(42) NOT NULL,
    requested_amount_atomic numeric(78,0) NOT NULL,
    actual_received_atomic numeric(78,0),
    call_gas_limit bigint NOT NULL,
    status character varying(32) NOT NULL,
    log_index integer,
    error_code character varying(64),
    error_hash character varying(66),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evm_withdrawal_batch_item_position_key UNIQUE (batch_id, item_index),
    CONSTRAINT evm_withdrawal_batch_item_order_key UNIQUE (batch_id, withdrawal_order_id),
    CONSTRAINT evm_withdrawal_batch_item_hash_key UNIQUE (batch_id, withdrawal_id_hash),
    CONSTRAINT evm_withdrawal_batch_item_amount_check CHECK (requested_amount_atomic > 0),
    CONSTRAINT evm_withdrawal_batch_item_received_check
        CHECK (actual_received_atomic IS NULL OR actual_received_atomic >= 0),
    CONSTRAINT evm_withdrawal_batch_item_status_check CHECK (
        status IN ('CREATED', 'SIGNED', 'SUBMITTED', 'CONFIRMED', 'RETRYABLE',
                   'FAILED', 'REORGED', 'MANUAL_REVIEW')),
    CONSTRAINT evm_withdrawal_batch_item_batch_fk FOREIGN KEY (tenant_id, batch_id)
        REFERENCES public.evm_withdrawal_batch(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT evm_withdrawal_batch_item_order_fk FOREIGN KEY (tenant_id, withdrawal_order_id)
        REFERENCES public.withdrawal_order(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT evm_withdrawal_batch_item_custody_fk FOREIGN KEY (tenant_id, custody_withdrawal_id)
        REFERENCES public.custody_withdrawal(tenant_id, id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX IF NOT EXISTS evm_withdrawal_batch_item_active_order_key
    ON public.evm_withdrawal_batch_item (withdrawal_order_id)
    WHERE status IN ('CREATED', 'SIGNED', 'SUBMITTED');

CREATE TABLE IF NOT EXISTS public.evm_withdrawal_batch_attempt (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    attempt_no integer NOT NULL,
    relayer_nonce numeric(78,0) NOT NULL,
    tx_hash character varying(128) NOT NULL,
    max_fee_per_gas numeric(78,0) NOT NULL,
    max_priority_fee_per_gas numeric(78,0) NOT NULL,
    gas_limit bigint NOT NULL,
    calldata_hash character varying(66) NOT NULL,
    signed_tx_ciphertext text NOT NULL,
    encryption_key_version character varying(64) NOT NULL,
    status character varying(32) NOT NULL,
    error_code character varying(64),
    error_message text,
    replaced_by_tx_hash character varying(128),
    rebroadcast_count integer DEFAULT 0 NOT NULL,
    last_rebroadcast_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    submitted_at timestamp with time zone,
    observed_at timestamp with time zone,
    CONSTRAINT evm_withdrawal_batch_attempt_number_key UNIQUE (batch_id, attempt_no),
    CONSTRAINT evm_withdrawal_batch_attempt_tx_key UNIQUE (tx_hash),
    CONSTRAINT evm_withdrawal_batch_attempt_rebroadcast_count_check CHECK (rebroadcast_count >= 0),
    CONSTRAINT evm_withdrawal_batch_attempt_status_check CHECK (
        status IN ('CREATED', 'SUBMITTED', 'PENDING', 'CONFIRMED', 'DROPPED',
                   'REPLACED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT evm_withdrawal_batch_attempt_batch_fk FOREIGN KEY (tenant_id, batch_id)
        REFERENCES public.evm_withdrawal_batch(tenant_id, id) ON DELETE RESTRICT
);

COMMIT;
