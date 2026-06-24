-- Drop legacy BTC-like withdraw_transaction tables after signing runtime cutover.
--
-- Preconditions:
--   1. scripts/migrate-bitcoinlike-signing-transaction-cutover.sql has run.
--   2. No BTC/LTC/DOGE/BCH legacy withdraw_transaction table contains active
--      rows with status WAITING(0), SIGNING(1), or SENT(2).
--   3. Terminal legacy rows have been copied into chain_signing_transaction for
--      audit/live-test consistency.
--
-- This script intentionally does not drop *_withdraw_record tables. Those
-- tables still act as external business request/notification compatibility
-- records while withdrawal_order is the chain-scoped operational state.

begin;

do $$
declare
    active_count bigint := 0;
    missing_count bigint := 0;
    table_count bigint;
begin
    if to_regclass('chain_signing_transaction') is null then
        raise exception 'refusing drop: chain_signing_transaction does not exist';
    end if;

    if to_regclass('btc_withdraw_transaction') is not null then
        select count(*) into table_count from btc_withdraw_transaction where status in (0, 1, 2);
        active_count := active_count + table_count;
        select count(*) into table_count
        from btc_withdraw_transaction legacy
        where legacy.status not in (0, 1, 2)
          and not exists (
              select 1 from chain_signing_transaction unified
              where unified.chain = 'BTC'
                and unified.business_type = 'LEGACY'
                and unified.business_no = 'btc_withdraw_transaction:' || legacy.id
          );
        missing_count := missing_count + table_count;
    end if;

    if to_regclass('ltc_withdraw_transaction') is not null then
        select count(*) into table_count from ltc_withdraw_transaction where status in (0, 1, 2);
        active_count := active_count + table_count;
        select count(*) into table_count
        from ltc_withdraw_transaction legacy
        where legacy.status not in (0, 1, 2)
          and not exists (
              select 1 from chain_signing_transaction unified
              where unified.chain = 'LTC'
                and unified.business_type = 'LEGACY'
                and unified.business_no = 'ltc_withdraw_transaction:' || legacy.id
          );
        missing_count := missing_count + table_count;
    end if;

    if to_regclass('doge_withdraw_transaction') is not null then
        select count(*) into table_count from doge_withdraw_transaction where status in (0, 1, 2);
        active_count := active_count + table_count;
        select count(*) into table_count
        from doge_withdraw_transaction legacy
        where legacy.status not in (0, 1, 2)
          and not exists (
              select 1 from chain_signing_transaction unified
              where unified.chain = 'DOGE'
                and unified.business_type = 'LEGACY'
                and unified.business_no = 'doge_withdraw_transaction:' || legacy.id
          );
        missing_count := missing_count + table_count;
    end if;

    if to_regclass('bch_withdraw_transaction') is not null then
        select count(*) into table_count from bch_withdraw_transaction where status in (0, 1, 2);
        active_count := active_count + table_count;
        select count(*) into table_count
        from bch_withdraw_transaction legacy
        where legacy.status not in (0, 1, 2)
          and not exists (
              select 1 from chain_signing_transaction unified
              where unified.chain = 'BCH'
                and unified.business_type = 'LEGACY'
                and unified.business_no = 'bch_withdraw_transaction:' || legacy.id
          );
        missing_count := missing_count + table_count;
    end if;

    if active_count > 0 then
        raise exception
            'refusing drop: % active legacy withdraw_transaction rows still exist',
            active_count;
    end if;

    if missing_count > 0 then
        raise exception
            'refusing drop: % terminal legacy withdraw_transaction rows are missing from chain_signing_transaction',
            missing_count;
    end if;
end $$;

drop table if exists btc_withdraw_transaction;
drop table if exists ltc_withdraw_transaction;
drop table if exists doge_withdraw_transaction;
drop table if exists bch_withdraw_transaction;

commit;
