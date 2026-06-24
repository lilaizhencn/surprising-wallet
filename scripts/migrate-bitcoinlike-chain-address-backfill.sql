-- Idempotent BTC/LTC/DOGE/BCH address backfill into chain_address.
--
-- Purpose:
--   Move the runtime address registry for BTC-like chains to the database-driven
--   chain_address table while retaining legacy address tables only as historical
--   compatibility data.
--
-- Safety:
--   - Does not delete or truncate legacy address tables.
--   - Does not overwrite an existing chain_address with a different address.
--   - Can be re-run.

BEGIN;

WITH source_addresses AS (
    SELECT 'BTC' AS chain, 'BTC' AS asset_symbol, 0 AS coin_type,
           user_id, biz, index AS address_index, address,
           derivation_path, status
    FROM btc_address

    UNION ALL
    SELECT 'LTC', 'LTC', 2,
           user_id, biz, index, address,
           derivation_path, status
    FROM ltc_address

    UNION ALL
    SELECT 'DOGE', 'DOGE', 3,
           user_id, biz, index, address,
           derivation_path, status
    FROM doge_address

    UNION ALL
    SELECT 'BCH', 'BCH', 145,
           user_id, biz, index, address,
           derivation_path, status
    FROM bch_address
)
INSERT INTO chain_address(
    chain, asset_symbol, account_id, user_id, biz, address_index, address,
    owner_address, derivation_path, wallet_role, enabled, created_at, updated_at
)
SELECT
    chain,
    asset_symbol,
    user_id::text,
    user_id,
    biz,
    address_index,
    address,
    NULL,
    CASE
        WHEN derivation_path IS NOT NULL AND derivation_path <> '' THEN derivation_path
        ELSE format('m/44/%s/%s/%s/%s', coin_type, biz, user_id, address_index)
    END,
    'DEPOSIT',
    status = 0,
    now(),
    now()
FROM source_addresses
ON CONFLICT (chain, asset_symbol, user_id, biz, address_index, wallet_role)
DO UPDATE SET
    account_id = excluded.account_id,
    address = excluded.address,
    owner_address = excluded.owner_address,
    derivation_path = excluded.derivation_path,
    enabled = excluded.enabled,
    updated_at = now();

COMMIT;
