-- Idempotent BTC/LTC/DOGE/BCH runtime UTXO backfill.
--
-- Purpose:
--   Copy currently spendable legacy UTXOs into the unified utxo_record table
--   before cutting runtime selection/locking/spend settlement to utxo_record.
--
-- Safety:
--   - Does not delete or truncate legacy tables.
--   - Does not overwrite LOCKED/SPENT utxo_record rows back to AVAILABLE.
--   - Does not create duplicate rows because utxo_record has
--     unique(chain, tx_hash, vout).
--   - Can be re-run.

BEGIN;

WITH source_utxos AS (
    SELECT 'BTC' AS chain, 'BTC' AS asset_symbol,
           tx_id, seq, address, balance, block_height, confirm_num,
           credited, create_date, update_date
    FROM btc_utxo_transaction
    WHERE spent = 0 AND spent_tx_id = 'unspent' AND balance > 0

    UNION ALL
    SELECT 'LTC', 'LTC',
           tx_id, seq, address, balance, block_height, confirm_num,
           credited, create_date, update_date
    FROM ltc_utxo_transaction
    WHERE spent = 0 AND spent_tx_id = 'unspent' AND balance > 0

    UNION ALL
    SELECT 'DOGE', 'DOGE',
           tx_id, seq, address, balance, block_height, confirm_num,
           credited, create_date, update_date
    FROM doge_utxo_transaction
    WHERE spent = 0 AND spent_tx_id = 'unspent' AND balance > 0

    UNION ALL
    SELECT 'BCH', 'BCH',
           tx_id, seq, address, balance, block_height, confirm_num,
           credited, create_date, update_date
    FROM bch_utxo_transaction
    WHERE spent = 0 AND spent_tx_id = 'unspent' AND balance > 0
)
INSERT INTO utxo_record (
    chain, asset_symbol, tx_hash, vout, address, amount, block_height,
    confirmations, state, lock_ref, spent_tx_hash, credited, created_at, updated_at
)
SELECT
    chain,
    asset_symbol,
    tx_id,
    seq,
    address,
    balance,
    block_height,
    coalesce(confirm_num, 0)::int,
    'AVAILABLE',
    NULL,
    NULL,
    coalesce(credited, false),
    coalesce(create_date, now()),
    coalesce(update_date, now())
FROM source_utxos
ON CONFLICT (chain, tx_hash, vout) DO UPDATE SET
    address = excluded.address,
    amount = excluded.amount,
    block_height = excluded.block_height,
    confirmations = greatest(utxo_record.confirmations, excluded.confirmations),
    state = CASE
        WHEN utxo_record.state IN ('LOCKED', 'SPENT') THEN utxo_record.state
        ELSE excluded.state
    END,
    credited = utxo_record.credited OR excluded.credited,
    updated_at = now();

COMMIT;
