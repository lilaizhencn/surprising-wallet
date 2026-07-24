-- ============================================================
-- Migration #2: Add remaining missing tables and columns
-- ============================================================

-- 1. chain_scan_block
-- ============================================================
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


-- 2. evm_collection_batch
-- ============================================================
CREATE TABLE IF NOT EXISTS public.evm_collection_batch (
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
    CONSTRAINT evm_collection_batch_tenant_id_key UNIQUE (tenant_id, id),
    CONSTRAINT evm_collection_batch_hash_key UNIQUE (chain, batch_hash),
    CONSTRAINT evm_collection_batch_item_count_check CHECK (item_count BETWEEN 1 AND 100),
    CONSTRAINT evm_collection_batch_status_check CHECK (status IN (
        'CREATED', 'LOCKED', 'SIMULATED', 'SIGNING',
        'SUBMITTED', 'BROADCAST_UNKNOWN', 'CONFIRMING', 'CONFIRMED',
        'PARTIAL_FAILED', 'FAILED', 'REORGED', 'MANUAL_REVIEW')),
    CONSTRAINT evm_collection_batch_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX IF NOT EXISTS evm_collection_batch_tx_key
    ON public.evm_collection_batch USING btree (chain, canonical_tx_hash)
    WHERE canonical_tx_hash IS NOT NULL;

CREATE INDEX IF NOT EXISTS evm_collection_batch_work_idx
    ON public.evm_collection_batch USING btree (chain, network, status, updated_at);


-- 3. evm_collection_batch_item
-- ============================================================
CREATE TABLE IF NOT EXISTS public.evm_collection_batch_item (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    item_index integer NOT NULL,
    collection_record_id bigint NOT NULL,
    custody_address_id uuid NOT NULL,
    authority_address character varying(42) NOT NULL,
    token_contract character varying(42) NOT NULL,
    recipient character varying(42) NOT NULL,
    requested_amount_atomic numeric(78,0) NOT NULL,
    actual_received_atomic numeric(78,0),
    authorization_included boolean DEFAULT false NOT NULL,
    authorization_nonce numeric(78,0),
    operation_nonce numeric(78,0) NOT NULL,
    signature_deadline timestamp with time zone NOT NULL,
    call_gas_limit bigint NOT NULL,
    status character varying(32) NOT NULL,
    log_index integer,
    error_code character varying(64),
    error_hash character varying(66),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evm_collection_batch_item_position_key UNIQUE (batch_id, item_index),
    CONSTRAINT evm_collection_batch_item_authority_key UNIQUE (batch_id, authority_address),
    CONSTRAINT evm_collection_batch_item_amount_check CHECK (requested_amount_atomic > 0),
    CONSTRAINT evm_collection_batch_item_received_check
        CHECK (actual_received_atomic IS NULL OR actual_received_atomic >= 0),
    CONSTRAINT evm_collection_batch_item_status_check CHECK (status IN (
        'CREATED', 'SIGNED', 'SUBMITTED', 'CONFIRMED',
        'FAILED', 'RETRYABLE', 'REORGED', 'MANUAL_REVIEW')),
    CONSTRAINT evm_collection_batch_item_batch_fk FOREIGN KEY (tenant_id, batch_id)
        REFERENCES public.evm_collection_batch(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT evm_collection_batch_item_custody_fk FOREIGN KEY (tenant_id, custody_address_id)
        REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT evm_collection_batch_item_collection_fk FOREIGN KEY (tenant_id, collection_record_id)
        REFERENCES public.collection_record(tenant_id, id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX IF NOT EXISTS evm_collection_batch_item_active_collection_key
    ON public.evm_collection_batch_item USING btree (collection_record_id)
    WHERE status IN ('CREATED', 'SIGNED', 'SUBMITTED');


-- 4. evm_collection_batch_attempt
-- ============================================================
CREATE TABLE IF NOT EXISTS public.evm_collection_batch_attempt (
    id uuid NOT NULL PRIMARY KEY,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    attempt_no integer NOT NULL,
    relayer_nonce numeric(78,0) NOT NULL,
    tx_hash character varying(128) NOT NULL,
    max_fee_per_gas numeric(78,0) NOT NULL,
    max_priority_fee_per_gas numeric(78,0) NOT NULL,
    gas_limit bigint NOT NULL,
    rpc_node_id bigint,
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
    CONSTRAINT evm_collection_batch_attempt_number_key UNIQUE (batch_id, attempt_no),
    CONSTRAINT evm_collection_batch_attempt_tx_key UNIQUE (tx_hash),
    CONSTRAINT evm_collection_batch_attempt_rebroadcast_count_check CHECK (rebroadcast_count >= 0),
    CONSTRAINT evm_collection_batch_attempt_status_check CHECK (status IN (
        'CREATED', 'SUBMITTED', 'PENDING', 'CONFIRMED',
        'DROPPED', 'REPLACED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT evm_collection_batch_attempt_batch_fk FOREIGN KEY (tenant_id, batch_id)
        REFERENCES public.evm_collection_batch(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT evm_collection_batch_attempt_rpc_fk FOREIGN KEY (rpc_node_id)
        REFERENCES public.chain_rpc_node(id) ON DELETE RESTRICT
);


-- 5. evm_withdrawal_batch
-- ============================================================
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
    CONSTRAINT evm_withdrawal_batch_status_check CHECK (status IN (
        'LOCKED', 'SIGNING', 'SUBMITTED', 'BROADCAST_UNKNOWN',
        'CONFIRMING', 'CONFIRMED', 'PARTIAL_FAILED', 'FAILED',
        'REORGED', 'MANUAL_REVIEW')),
    CONSTRAINT evm_withdrawal_batch_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES public.custody_tenant(id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX IF NOT EXISTS evm_withdrawal_batch_tx_key
    ON public.evm_withdrawal_batch USING btree (chain, canonical_tx_hash)
    WHERE canonical_tx_hash IS NOT NULL;

CREATE INDEX IF NOT EXISTS evm_withdrawal_batch_work_idx
    ON public.evm_withdrawal_batch USING btree (chain, network, status, updated_at);


-- 6. evm_withdrawal_batch_item
-- ============================================================
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
    CONSTRAINT evm_withdrawal_batch_item_status_check CHECK (status IN (
        'CREATED', 'SIGNED', 'SUBMITTED', 'CONFIRMED',
        'RETRYABLE', 'FAILED', 'REORGED', 'MANUAL_REVIEW')),
    CONSTRAINT evm_withdrawal_batch_item_batch_fk FOREIGN KEY (tenant_id, batch_id)
        REFERENCES public.evm_withdrawal_batch(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT evm_withdrawal_batch_item_order_fk FOREIGN KEY (tenant_id, withdrawal_order_id)
        REFERENCES public.withdrawal_order(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT evm_withdrawal_batch_item_custody_fk FOREIGN KEY (tenant_id, custody_withdrawal_id)
        REFERENCES public.custody_withdrawal(tenant_id, id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX IF NOT EXISTS evm_withdrawal_batch_item_active_order_key
    ON public.evm_withdrawal_batch_item USING btree (withdrawal_order_id)
    WHERE status IN ('CREATED', 'SIGNED', 'SUBMITTED');


-- 7. evm_withdrawal_batch_attempt
-- ============================================================
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
    CONSTRAINT evm_withdrawal_batch_attempt_status_check CHECK (status IN (
        'CREATED', 'SUBMITTED', 'PENDING', 'CONFIRMED',
        'DROPPED', 'REPLACED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT evm_withdrawal_batch_attempt_batch_fk FOREIGN KEY (tenant_id, batch_id)
        REFERENCES public.evm_withdrawal_batch(tenant_id, id) ON DELETE RESTRICT
);


-- 8. Add missing columns to collection_record
-- ============================================================
ALTER TABLE public.collection_record
    ADD COLUMN IF NOT EXISTS tenant_id uuid,
    ADD COLUMN IF NOT EXISTS custody_address_id uuid;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.collection_record'::regclass
           AND conname = 'collection_record_tenant_id_key'
    ) THEN
        ALTER TABLE public.collection_record
            ADD CONSTRAINT collection_record_tenant_id_key UNIQUE (tenant_id, id);
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.collection_record'::regclass
           AND conname = 'collection_record_tenant_fk'
    ) THEN
        ALTER TABLE public.collection_record
            ADD CONSTRAINT collection_record_tenant_fk FOREIGN KEY (tenant_id)
            REFERENCES public.custody_tenant(id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.collection_record'::regclass
           AND conname = 'collection_record_custody_fk'
    ) THEN
        ALTER TABLE public.collection_record
            ADD CONSTRAINT collection_record_custody_fk FOREIGN KEY (tenant_id, custody_address_id)
            REFERENCES public.custody_address(tenant_id, id) ON DELETE RESTRICT;
    END IF;
END;
$$;


-- 9. Add missing block_hash to utxo_record
-- ============================================================
ALTER TABLE public.utxo_record
    ADD COLUMN IF NOT EXISTS block_hash character varying(128);
