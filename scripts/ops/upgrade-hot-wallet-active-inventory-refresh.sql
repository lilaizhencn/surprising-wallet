-- Support bounded background inventory polling on existing Wallet databases.
-- Run outside an explicit transaction as the maintenance owner.
CREATE INDEX CONCURRENTLY IF NOT EXISTS custody_hot_wallet_active_refresh_idx
    ON public.custody_hot_wallet(id) WHERE status = 'ACTIVE';
