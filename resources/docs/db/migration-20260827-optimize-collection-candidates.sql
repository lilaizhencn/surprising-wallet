CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chain_address_collection_candidates
    ON public.chain_address (chain)
    WHERE enabled = true AND tenant_id IS NOT NULL AND wallet_role = 'DEPOSIT' AND user_id <> 0;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_collection_record_collection_balance
    ON public.collection_record (chain, tenant_id, asset_symbol, lower(from_address))
    INCLUDE (amount, status)
    WHERE tenant_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_custody_address_collection_candidates
    ON public.custody_address (chain)
    WHERE status = 'ACTIVE';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_deposit_record_collection_balance
    ON public.deposit_record (chain, tenant_id, asset_symbol, lower(to_address))
    INCLUDE (amount)
    WHERE tenant_id IS NOT NULL AND credited = true;
