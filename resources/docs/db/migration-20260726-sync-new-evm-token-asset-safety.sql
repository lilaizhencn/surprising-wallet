-- Keep newly synchronized mainnet token assets inactive until their matching
-- token_config rows and production controls are deliberately enabled.
--
-- This follows migration-20260726-sync-new-evm-chain-config.sql. It is a
-- separate immutable migration because the first migration has already been
-- applied to the development server.

BEGIN;

SELECT pg_advisory_xact_lock(hashtext('surprising-wallet:20260726:new-evm-token-asset-safety'));

UPDATE public.chain_asset
SET active = false,
    updated_at = now()
WHERE chain IN (
    'BERACHAIN', 'GNOSIS', 'CELO', 'MONAD', 'WORLD_CHAIN', 'INK', 'TAIKO',
    'SONEIUM', 'MODE', 'LISK', 'KATANA', 'MEGAETH', 'X_LAYER', 'DEGEN',
    'ROBINHOOD_CHAIN', 'ETHERLINK', 'IOTA_EVM', 'OASIS_EMERALD', 'CRONOS',
    'SONIC', 'PULSECHAIN', 'ZETACHAIN', 'CORE', 'SOMNIA', 'RONIN', 'CHILIZ',
    'IOTEX', 'KAIA', 'PLASMA', 'STORY', 'SEI', 'CONFLUX',
    'VECTOR_SMART_CHAIN', 'KROWN'
)
  AND native_asset = false
  AND active = true;

COMMIT;
