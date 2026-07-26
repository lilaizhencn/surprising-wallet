BEGIN;

-- Token and asset switches must stay consistent with the enabled chain profiles.
UPDATE public.token_config
SET enabled = chain = 'ETH' AND lower(network) = 'devtest',
    collect_enabled = chain = 'ETH' AND lower(network) = 'devtest',
    updated_at = now();

UPDATE public.chain_asset
SET active = chain IN ('BTC', 'ETH'),
    updated_at = now();

COMMIT;
