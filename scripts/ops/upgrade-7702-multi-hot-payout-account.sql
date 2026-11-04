-- Allow multiple EIP-7702 payout authorities for one tenant and chain.
-- Existing rows remain bound to their original authority address.
BEGIN;
DO $$
BEGIN
    IF to_regclass('public.evm_7702_payout_account') IS NULL THEN
        RAISE EXCEPTION 'evm_7702_payout_account must exist before migration';
    END IF;
END $$;
LOCK TABLE public.evm_7702_payout_account IN ACCESS EXCLUSIVE MODE;
ALTER TABLE public.evm_7702_payout_account
    DROP CONSTRAINT IF EXISTS evm_7702_payout_account_tenant_chain_key;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'public.evm_7702_payout_account'::regclass
           AND conname = 'evm_7702_payout_account_tenant_authority_key'
    ) THEN
        ALTER TABLE public.evm_7702_payout_account
            ADD CONSTRAINT evm_7702_payout_account_tenant_authority_key
            UNIQUE (tenant_id, chain, authority_address);
    END IF;
END $$;
COMMIT;
