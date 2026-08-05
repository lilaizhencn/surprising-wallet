BEGIN;

CREATE TABLE IF NOT EXISTS wallet_task_lease (
    task_name character varying(256) NOT NULL,
    owner_id character varying(256) NOT NULL,
    lease_until timestamp with time zone NOT NULL,
    heartbeat_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT wallet_task_lease_pkey PRIMARY KEY (task_name)
);

CREATE TABLE IF NOT EXISTS wallet_outbox (
    id uuid NOT NULL,
    tenant_id uuid,
    topic character varying(64) NOT NULL,
    aggregate_type character varying(64) NOT NULL,
    aggregate_id character varying(192) NOT NULL,
    dedupe_key character varying(256) NOT NULL,
    payload jsonb NOT NULL,
    status character varying(24) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamp with time zone NOT NULL DEFAULT now(),
    locked_by character varying(256),
    locked_at timestamp with time zone,
    last_error text,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    dispatched_at timestamp with time zone,
    CONSTRAINT wallet_outbox_pkey PRIMARY KEY (id),
    CONSTRAINT wallet_outbox_status_check CHECK (status IN ('PENDING', 'DISPATCHING', 'DISPATCHED', 'FAILED', 'DEAD')),
    CONSTRAINT wallet_outbox_attempt_check CHECK (attempt_count >= 0),
    CONSTRAINT wallet_outbox_dedupe_key_check CHECK (length(trim(dedupe_key)) > 0),
    CONSTRAINT wallet_outbox_topic_dedupe_key UNIQUE (topic, dedupe_key)
);

ALTER TABLE withdrawal_order
    ADD COLUMN IF NOT EXISTS lease_owner character varying(256),
    ADD COLUMN IF NOT EXISTS lease_until timestamp with time zone,
    ADD COLUMN IF NOT EXISTS attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at timestamp with time zone NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS last_error_code character varying(64),
    ADD COLUMN IF NOT EXISTS last_attempt_at timestamp with time zone;

ALTER TABLE chain_signing_transaction
    ADD COLUMN IF NOT EXISTS broadcast_owner character varying(256),
    ADD COLUMN IF NOT EXISTS broadcast_lease_until timestamp with time zone;

CREATE INDEX IF NOT EXISTS wallet_outbox_due_idx
    ON wallet_outbox(status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'DISPATCHING');

CREATE INDEX IF NOT EXISTS wallet_outbox_tenant_time_idx
    ON wallet_outbox(tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS withdrawal_order_fair_queue_idx
    ON withdrawal_order(chain, asset_symbol, status, tenant_id, id)
    WHERE tenant_id IS NOT NULL AND status IN ('FROZEN', 'RETRYING');

CREATE INDEX IF NOT EXISTS withdrawal_order_lease_idx
    ON withdrawal_order(status, lease_until, next_attempt_at, id)
    WHERE status IN ('PROCESSING', 'SIGNING', 'BROADCAST_UNKNOWN');

CREATE INDEX IF NOT EXISTS chain_signing_transaction_broadcast_lease_idx
    ON chain_signing_transaction(status, broadcast_lease_until, id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'custody_withdrawal_amount_check') THEN
        ALTER TABLE custody_withdrawal
            ADD CONSTRAINT custody_withdrawal_amount_check CHECK (amount > 0) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'custody_withdrawal_fee_check') THEN
        ALTER TABLE custody_withdrawal
            ADD CONSTRAINT custody_withdrawal_fee_check CHECK (fee >= 0) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ledger_balance_non_negative_check') THEN
        ALTER TABLE ledger_balance
            ADD CONSTRAINT ledger_balance_non_negative_check CHECK (
                available_balance >= 0 AND locked_balance >= 0 AND total_balance >= 0
            ) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ledger_balance_invariant_check') THEN
        ALTER TABLE ledger_balance
            ADD CONSTRAINT ledger_balance_invariant_check CHECK (
                total_balance = available_balance + locked_balance
            ) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'withdrawal_order_amount_check') THEN
        ALTER TABLE withdrawal_order
            ADD CONSTRAINT withdrawal_order_amount_check CHECK (amount > 0 AND fee >= 0) NOT VALID;
    END IF;
END
$$;

COMMIT;
