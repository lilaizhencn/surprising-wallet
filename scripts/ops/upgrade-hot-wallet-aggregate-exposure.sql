-- Install the aggregate active hot-wallet cap before deploying its routing code.
-- Run as the Wallet maintenance owner and reapply resources/infra/approval/database-roles.sql
-- for customized runtime roles before restarting Wallet.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
CREATE TABLE IF NOT EXISTS public.custody_hot_wallet_exposure_limit (
    tenant_id uuid NOT NULL,
    chain varchar(32) NOT NULL,
    asset_symbol varchar(32) NOT NULL,
    maximum_active_balance numeric(78,24) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,chain,asset_symbol),
    FOREIGN KEY (tenant_id,chain) REFERENCES public.custody_tenant_chain(tenant_id,chain) ON DELETE RESTRICT,
    FOREIGN KEY (chain,asset_symbol) REFERENCES public.chain_asset(chain,symbol) ON DELETE RESTRICT,
    CHECK (maximum_active_balance > 0)
);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
        WHERE table_schema='public' AND table_name='custody_hot_wallet_exposure_limit'
          AND column_name='maximum_active_balance' AND data_type='numeric') THEN
        RAISE EXCEPTION 'aggregate hot wallet exposure schema differs from baseline';
    END IF;
END $$;
-- Existing active routes retain their prior sum of individual wallet caps. New wallets
-- require a deliberate tenant-level increase before activation.
INSERT INTO public.custody_hot_wallet_exposure_limit
    (tenant_id,chain,asset_symbol,maximum_active_balance)
SELECT p.tenant_id,p.chain,p.asset_symbol,sum(p.maximum_balance)
  FROM public.custody_hot_wallet_asset_policy p
  JOIN public.custody_hot_wallet w ON w.tenant_id=p.tenant_id AND w.id=p.wallet_id
 WHERE w.status='ACTIVE'
 GROUP BY p.tenant_id,p.chain,p.asset_symbol
ON CONFLICT (tenant_id,chain,asset_symbol) DO NOTHING;
COMMIT;
