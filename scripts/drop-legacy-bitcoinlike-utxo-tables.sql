-- Drop legacy BTC-like UTXO tables after the unified utxo_record migration.
--
-- Execute only after:
--   1. scripts/migrate-bitcoinlike-utxo-record-backfill.sql has completed.
--   2. Runtime has used utxo_record for deposit/withdraw/collection successfully.
--   3. A DB backup or PITR restore point exists.
--
-- This script intentionally does not drop address / withdraw_record /
-- withdraw_transaction tables. Those legacy tables still participate in
-- routing/signing compatibility. Only old *_utxo_transaction tables are removed.

BEGIN;

DO $$
DECLARE
    cfg record;
    missing_count bigint;
BEGIN
    FOR cfg IN
        SELECT *
        FROM (VALUES
            ('BTC', 'btc_utxo_transaction'),
            ('LTC', 'ltc_utxo_transaction'),
            ('DOGE', 'doge_utxo_transaction'),
            ('BCH', 'bch_utxo_transaction')
        ) AS t(chain, table_name)
    LOOP
        IF to_regclass(cfg.table_name) IS NOT NULL THEN
            EXECUTE format($sql$
                SELECT count(*)
                FROM %I legacy
                WHERE legacy.spent = 0
                  AND legacy.spent_tx_id = 'unspent'
                  AND legacy.balance > 0
                  AND NOT EXISTS (
                      SELECT 1
                      FROM utxo_record unified
                      WHERE unified.chain = %L
                        AND unified.tx_hash = legacy.tx_id
                        AND unified.vout = legacy.seq
                        AND unified.state = 'AVAILABLE'
                  )
            $sql$, cfg.table_name, cfg.chain)
            INTO missing_count;

            IF missing_count > 0 THEN
                RAISE EXCEPTION
                    'refusing to drop %, % spendable legacy UTXOs are missing from utxo_record',
                    cfg.table_name, missing_count;
            END IF;
        END IF;
    END LOOP;
END $$;

DROP TABLE IF EXISTS
    btc_utxo_transaction,
    ltc_utxo_transaction,
    doge_utxo_transaction,
    bch_utxo_transaction;

COMMIT;
