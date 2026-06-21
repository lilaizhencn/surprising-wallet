-- Multi-chain wallet schema additions
-- Safe to apply incrementally; uses PostgreSQL IF NOT EXISTS semantics.

create table if not exists chain_asset (
    id bigserial primary key,
    chain varchar(32) not null,
    symbol varchar(32) not null,
    asset_kind varchar(32) not null,
    contract_address varchar(128),
    decimals integer not null default 18,
    native_asset boolean not null default false,
    active boolean not null default true,
    min_transfer numeric(78, 0),
    min_withdraw numeric(78, 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, symbol)
);

create table if not exists chain_profile (
    id bigserial primary key,
    chain varchar(32) not null,
    network varchar(32) not null,
    family varchar(32) not null,
    runtime_currency_id integer not null,
    bip44_coin_type integer not null,
    native_symbol varchar(32) not null,
    rpc_url varchar(512),
    explorer_url varchar(512),
    deposit_confirmations integer not null default 1,
    withdraw_confirmations integer not null default 1,
    default_fee_rate bigint,
    dust_threshold bigint,
    enabled boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, network),
    unique (runtime_currency_id, network)
);

insert into chain_profile(
    chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
    rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
    default_fee_rate, dust_threshold, enabled
)
values (
    'LTC', 'testnet', 'bitcoin-like', 24, 2, 'LTC',
    null, 'https://litecoinspace.org/testnet/tx/',
    1, 6, 2, 1000, true
)
on conflict (chain, network) do update
set family = excluded.family,
    runtime_currency_id = excluded.runtime_currency_id,
    bip44_coin_type = excluded.bip44_coin_type,
    native_symbol = excluded.native_symbol,
    explorer_url = excluded.explorer_url,
    deposit_confirmations = excluded.deposit_confirmations,
    withdraw_confirmations = excluded.withdraw_confirmations,
    default_fee_rate = excluded.default_fee_rate,
    dust_threshold = excluded.dust_threshold,
    enabled = excluded.enabled,
    updated_at = now();

insert into chain_profile(
    chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
    rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
    default_fee_rate, dust_threshold, enabled
)
values (
    'LTC', 'mainnet', 'bitcoin-like', 24, 2, 'LTC',
    null, 'https://litecoinspace.org/tx/',
    1, 6, 2, 1000, true
)
on conflict (chain, network) do update
set family = excluded.family,
    runtime_currency_id = excluded.runtime_currency_id,
    bip44_coin_type = excluded.bip44_coin_type,
    native_symbol = excluded.native_symbol,
    explorer_url = excluded.explorer_url,
    deposit_confirmations = excluded.deposit_confirmations,
    withdraw_confirmations = excluded.withdraw_confirmations,
    default_fee_rate = excluded.default_fee_rate,
    dust_threshold = excluded.dust_threshold,
    enabled = excluded.enabled,
    updated_at = now();

insert into chain_asset(chain, symbol, asset_kind, decimals, native_asset, active, min_transfer, min_withdraw)
values ('LTC', 'LTC', 'NATIVE', 8, true, true, 100000, 100000)
on conflict (chain, symbol) do update
set decimals = excluded.decimals,
    native_asset = excluded.native_asset,
    active = excluded.active,
    updated_at = now();

insert into chain_profile(
    chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
    rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
    default_fee_rate, dust_threshold, enabled
)
values (
    'DOGE', 'testnet', 'bitcoin-like', 41, 3, 'DOGE',
    null, 'https://doge-testnet-explorer.qed.me/tx/',
    6, 12, 1000, 1000000, true
)
on conflict (chain, network) do update
set family = excluded.family,
    runtime_currency_id = excluded.runtime_currency_id,
    bip44_coin_type = excluded.bip44_coin_type,
    native_symbol = excluded.native_symbol,
    explorer_url = excluded.explorer_url,
    deposit_confirmations = excluded.deposit_confirmations,
    withdraw_confirmations = excluded.withdraw_confirmations,
    default_fee_rate = excluded.default_fee_rate,
    dust_threshold = excluded.dust_threshold,
    enabled = excluded.enabled,
    updated_at = now();

insert into chain_profile(
    chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
    rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
    default_fee_rate, dust_threshold, enabled
)
values (
    'DOGE', 'mainnet', 'bitcoin-like', 41, 3, 'DOGE',
    null, 'https://dogechain.info/tx/',
    6, 12, 1000, 1000000, true
)
on conflict (chain, network) do update
set family = excluded.family,
    runtime_currency_id = excluded.runtime_currency_id,
    bip44_coin_type = excluded.bip44_coin_type,
    native_symbol = excluded.native_symbol,
    explorer_url = excluded.explorer_url,
    deposit_confirmations = excluded.deposit_confirmations,
    withdraw_confirmations = excluded.withdraw_confirmations,
    default_fee_rate = excluded.default_fee_rate,
    dust_threshold = excluded.dust_threshold,
    enabled = excluded.enabled,
    updated_at = now();

insert into chain_asset(chain, symbol, asset_kind, decimals, native_asset, active, min_transfer, min_withdraw)
values ('DOGE', 'DOGE', 'NATIVE', 8, true, true, 1000000, 1000000)
on conflict (chain, symbol) do update
set decimals = excluded.decimals,
    native_asset = excluded.native_asset,
    active = excluded.active,
    min_transfer = excluded.min_transfer,
    min_withdraw = excluded.min_withdraw,
    updated_at = now();

create table if not exists ltc_address (like btc_address including defaults including identity);
create table if not exists ltc_utxo_transaction (like btc_utxo_transaction including defaults including identity);
create table if not exists ltc_withdraw_record (like btc_withdraw_record including defaults including identity);
create table if not exists ltc_withdraw_transaction (like btc_withdraw_transaction including defaults including identity);
create table if not exists doge_address (like btc_address including defaults including identity);
create table if not exists doge_utxo_transaction (like btc_utxo_transaction including defaults including identity);
create table if not exists doge_withdraw_record (like btc_withdraw_record including defaults including identity);
create table if not exists doge_withdraw_transaction (like btc_withdraw_transaction including defaults including identity);

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'pk_ltc_address') then
        alter table ltc_address add constraint pk_ltc_address primary key (id);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'uq_ltc_address_address') then
        alter table ltc_address add constraint uq_ltc_address_address unique (address);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'uq_ltc_address_user_biz_index') then
        alter table ltc_address add constraint uq_ltc_address_user_biz_index unique (user_id, biz, index);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'pk_ltc_utxo_transaction') then
        alter table ltc_utxo_transaction add constraint pk_ltc_utxo_transaction primary key (id);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'uq_ltc_utxo_transaction_tx_seq') then
        alter table ltc_utxo_transaction add constraint uq_ltc_utxo_transaction_tx_seq unique (tx_id, seq);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'pk_ltc_withdraw_record') then
        alter table ltc_withdraw_record add constraint pk_ltc_withdraw_record primary key (id);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'uq_ltc_withdraw_record_withdraw_id') then
        alter table ltc_withdraw_record add constraint uq_ltc_withdraw_record_withdraw_id unique (withdraw_id);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'pk_ltc_withdraw_transaction') then
        alter table ltc_withdraw_transaction add constraint pk_ltc_withdraw_transaction primary key (id);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'pk_doge_address') then
        alter table doge_address add constraint pk_doge_address primary key (id);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'uq_doge_address_address') then
        alter table doge_address add constraint uq_doge_address_address unique (address);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'uq_doge_address_user_biz_index') then
        alter table doge_address add constraint uq_doge_address_user_biz_index unique (user_id, biz, index);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'pk_doge_utxo_transaction') then
        alter table doge_utxo_transaction add constraint pk_doge_utxo_transaction primary key (id);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'uq_doge_utxo_transaction_tx_seq') then
        alter table doge_utxo_transaction add constraint uq_doge_utxo_transaction_tx_seq unique (tx_id, seq);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'pk_doge_withdraw_record') then
        alter table doge_withdraw_record add constraint pk_doge_withdraw_record primary key (id);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'uq_doge_withdraw_record_withdraw_id') then
        alter table doge_withdraw_record add constraint uq_doge_withdraw_record_withdraw_id unique (withdraw_id);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'pk_doge_withdraw_transaction') then
        alter table doge_withdraw_transaction add constraint pk_doge_withdraw_transaction primary key (id);
    end if;
end $$;

create index if not exists idx_ltc_utxo_available
    on ltc_utxo_transaction (status, confirm_num, spent_tx_id);
create index if not exists idx_ltc_utxo_address
    on ltc_utxo_transaction (address);
create index if not exists idx_ltc_withdraw_record_tx_id
    on ltc_withdraw_record (tx_id);
create index if not exists idx_ltc_withdraw_record_status
    on ltc_withdraw_record (status);
create index if not exists idx_ltc_withdraw_transaction_tx_id
    on ltc_withdraw_transaction (tx_id);
create index if not exists idx_ltc_withdraw_transaction_status
    on ltc_withdraw_transaction (status);
create index if not exists idx_doge_utxo_available
    on doge_utxo_transaction (status, confirm_num, spent_tx_id);
create index if not exists idx_doge_utxo_address
    on doge_utxo_transaction (address);
create index if not exists idx_doge_withdraw_record_tx_id
    on doge_withdraw_record (tx_id);
create index if not exists idx_doge_withdraw_record_status
    on doge_withdraw_record (status);
create index if not exists idx_doge_withdraw_transaction_tx_id
    on doge_withdraw_transaction (tx_id);
create index if not exists idx_doge_withdraw_transaction_status
    on doge_withdraw_transaction (status);

insert into best_block_height(currency, height, interval_time)
values (24, 0, 300000)
on conflict (currency) do nothing;

insert into currency_balance(currency_index, balance)
values (24, 0)
on conflict (currency_index) do nothing;

insert into best_block_height(currency, height, interval_time)
values (41, 0, 60000)
on conflict (currency) do nothing;

insert into currency_balance(currency_index, balance)
values (41, 0)
on conflict (currency_index) do nothing;

create table if not exists token_registry (
    id bigserial primary key,
    chain varchar(32) not null,
    symbol varchar(32) not null,
    contract_address varchar(128) not null,
    decimals integer not null default 18,
    standard varchar(32) not null,
    native_asset boolean not null default false,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, symbol),
    unique (chain, contract_address)
);

create table if not exists token_config (
    id bigserial primary key,
    chain varchar(32) not null,
    symbol varchar(32) not null,
    standard varchar(32) not null,
    contract_address varchar(128) not null,
    decimals integer not null,
    enabled boolean not null default true,
    min_deposit numeric(78, 18),
    min_withdraw numeric(78, 18),
    collect_enabled boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, symbol),
    unique (chain, contract_address)
);

alter table token_config add column if not exists network varchar(32);
alter table token_config add column if not exists token_standard varchar(32);
alter table token_config add column if not exists contract_address_base58 varchar(128);
alter table token_config add column if not exists contract_address_hex varchar(128);
alter table token_config add column if not exists min_deposit_amount numeric(78, 18);
alter table token_config add column if not exists min_withdraw_amount numeric(78, 18);
alter table token_config add column if not exists collect_threshold numeric(78, 18);
alter table token_config add column if not exists gas_strategy varchar(64);
alter table token_config add column if not exists confirmation_required integer;

create table if not exists chain_scan_height (
    id bigserial primary key,
    chain varchar(32) not null,
    scanner_name varchar(64) not null,
    best_height bigint not null default 0,
    safe_height bigint not null default 0,
    status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, scanner_name)
);

create table if not exists utxo_record (
    id bigserial primary key,
    chain varchar(32) not null,
    asset_symbol varchar(32) not null,
    tx_hash varchar(128) not null,
    vout integer not null,
    address varchar(160) not null,
    amount numeric(78, 18) not null,
    block_height bigint not null,
    confirmations integer not null default 0,
    state varchar(32) not null default 'AVAILABLE',
    lock_ref varchar(128),
    spent_tx_hash varchar(128),
    credited boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, tx_hash, vout)
);

create index if not exists idx_utxo_record_spendable
    on utxo_record(chain, asset_symbol, state, confirmations);
create index if not exists idx_utxo_record_address
    on utxo_record(chain, address);

create table if not exists hot_wallet_address (
    id bigserial primary key,
    chain varchar(32) not null,
    asset_symbol varchar(32) not null,
    address varchar(160) not null,
    address_index bigint not null default 0,
    wallet_role varchar(32) not null default 'HOT_WITHDRAW',
    enabled boolean not null default true,
    kms_key_ref varchar(256),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, asset_symbol, wallet_role)
);

create table if not exists deposit_record (
    id bigserial primary key,
    chain varchar(32) not null,
    asset_symbol varchar(32) not null,
    tx_hash varchar(128) not null,
    log_index bigint not null default 0,
    from_address varchar(160),
    to_address varchar(160) not null,
    contract_address varchar(128),
    amount numeric(78, 18) not null,
    block_height bigint not null,
    confirmations integer not null default 0,
    status varchar(32) not null default 'DETECTED',
    credited boolean not null default false,
    credited_at timestamptz,
    raw_payload text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, tx_hash, log_index)
);

create table if not exists withdrawal_order (
    id bigserial primary key,
    order_no varchar(96) not null,
    user_id bigint not null,
    chain varchar(32) not null,
    asset_symbol varchar(32) not null,
    from_address varchar(160),
    to_address varchar(160) not null,
    amount numeric(78, 18) not null,
    fee numeric(78, 18) not null default 0,
    tx_hash varchar(128),
    status varchar(32) not null default 'CREATED',
    error_message text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

do $$
begin
    if exists (select 1 from pg_constraint where conname = 'withdrawal_order_order_no_key') then
        alter table withdrawal_order drop constraint withdrawal_order_order_no_key;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'uq_withdrawal_order_chain_order_no') then
        alter table withdrawal_order
            add constraint uq_withdrawal_order_chain_order_no unique (chain, order_no);
    end if;
end $$;

create table if not exists evm_nonce (
    id bigserial primary key,
    chain varchar(32) not null,
    address varchar(128) not null,
    chain_nonce bigint not null default 0,
    reserved_nonce bigint not null default 0,
    status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, address)
);

create table if not exists evm_tx (
    id bigserial primary key,
    chain varchar(32) not null,
    tx_hash varchar(128) not null,
    from_address varchar(128) not null,
    to_address varchar(128) not null,
    asset_symbol varchar(32) not null,
    contract_address varchar(128),
    amount numeric(78, 18) not null default 0,
    fee numeric(78, 18) not null default 0,
    nonce bigint,
    block_height bigint,
    confirmations integer not null default 0,
    status varchar(32) not null default 'NEW',
    raw_payload text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, tx_hash)
);

create table if not exists evm_transaction (
    id bigserial primary key,
    chain varchar(32) not null,
    tx_hash varchar(128) not null,
    from_address varchar(128) not null,
    to_address varchar(128) not null,
    asset_symbol varchar(32) not null,
    contract_address varchar(128),
    amount numeric(78, 18) not null default 0,
    fee numeric(78, 18) not null default 0,
    nonce bigint,
    block_height bigint,
    confirmations integer not null default 0,
    status varchar(32) not null default 'CREATED',
    raw_payload text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, tx_hash)
);

create table if not exists evm_token_transfer (
    id bigserial primary key,
    chain varchar(32) not null,
    tx_hash varchar(128) not null,
    log_index bigint not null,
    token_symbol varchar(32) not null,
    contract_address varchar(128) not null,
    from_address varchar(128) not null,
    to_address varchar(128) not null,
    amount numeric(78, 18) not null default 0,
    block_height bigint not null,
    confirmations integer not null default 0,
    status varchar(32) not null default 'DETECTED',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, tx_hash, log_index)
);

create table if not exists tron_tx (
    id bigserial primary key,
    chain varchar(32) not null,
    tx_hash varchar(128) not null,
    from_address varchar(128) not null,
    to_address varchar(128) not null,
    asset_symbol varchar(32) not null,
    contract_address varchar(128),
    amount numeric(78, 18) not null default 0,
    fee numeric(78, 18) not null default 0,
    block_height bigint,
    confirmations integer not null default 0,
    status varchar(32) not null default 'NEW',
    raw_payload text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, tx_hash)
);

create table if not exists tron_transaction (
    id bigserial primary key,
    chain varchar(32) not null default 'TRON',
    tx_hash varchar(128) not null,
    from_address varchar(128) not null,
    to_address varchar(128) not null,
    asset_symbol varchar(32) not null,
    contract_address varchar(128),
    amount numeric(78, 18) not null default 0,
    fee numeric(78, 18) not null default 0,
    block_height bigint,
    confirmations integer not null default 0,
    status varchar(32) not null default 'CREATED',
    raw_payload text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, tx_hash)
);

create table if not exists tron_token_transfer (
    id bigserial primary key,
    chain varchar(32) not null default 'TRON',
    tx_hash varchar(128) not null,
    log_index bigint not null default 0,
    token_symbol varchar(32) not null,
    contract_address varchar(128) not null,
    from_address varchar(128) not null,
    to_address varchar(128) not null,
    amount numeric(78, 18) not null default 0,
    block_height bigint not null,
    confirmations integer not null default 0,
    status varchar(32) not null default 'DETECTED',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, tx_hash, log_index)
);

create table if not exists collection_record (
    id bigserial primary key,
    collection_no varchar(96) not null,
    chain varchar(32) not null,
    asset_symbol varchar(32) not null,
    from_address varchar(160) not null,
    to_address varchar(160) not null,
    amount numeric(78, 18) not null default 0,
    fee numeric(78, 18) not null default 0,
    tx_hash varchar(128),
    status varchar(32) not null default 'CREATED',
    error_message text,
    raw_payload text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, asset_symbol, from_address, to_address, tx_hash)
);

do $$
begin
    if exists (select 1 from pg_constraint where conname = 'collection_record_collection_no_key') then
        alter table collection_record drop constraint collection_record_collection_no_key;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'uq_collection_record_chain_collection_no') then
        alter table collection_record
            add constraint uq_collection_record_chain_collection_no unique (chain, collection_no);
    end if;
end $$;

create table if not exists gas_topup_task (
    id bigserial primary key,
    task_no varchar(96) not null,
    chain varchar(32) not null,
    target_address varchar(160) not null,
    source_address varchar(160),
    amount numeric(78, 18) not null default 0,
    tx_hash varchar(128),
    status varchar(32) not null default 'CREATED',
    reason varchar(256),
    retry_count integer not null default 0,
    raw_payload text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (task_no),
    unique (chain, target_address, status)
);

create table if not exists sol_transaction (
    id bigserial primary key,
    chain varchar(32) not null default 'SOLANA',
    signature varchar(128) not null,
    from_address varchar(128),
    to_address varchar(128) not null,
    asset_symbol varchar(32) not null,
    mint_address varchar(128),
    amount numeric(78, 18) not null default 0,
    fee_lamports bigint not null default 0,
    slot bigint,
    confirmations integer not null default 0,
    status varchar(32) not null default 'CREATED',
    raw_payload text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, signature)
);

create table if not exists ton_transaction (
    id bigserial primary key,
    chain varchar(32) not null default 'TON',
    tx_hash varchar(128) not null,
    from_address varchar(160),
    to_address varchar(160) not null,
    asset_symbol varchar(32) not null,
    jetton_master varchar(160),
    amount numeric(78, 18) not null default 0,
    fee_nano bigint not null default 0,
    logical_time numeric(78, 0),
    confirmations integer not null default 0,
    status varchar(32) not null default 'CREATED',
    raw_payload text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, tx_hash)
);

create table if not exists ledger_balance (
    id bigserial primary key,
    chain varchar(32) not null,
    asset_symbol varchar(32) not null,
    account_id varchar(128) not null,
    available_balance numeric(78, 18) not null default 0,
    locked_balance numeric(78, 18) not null default 0,
    total_balance numeric(78, 18) not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, asset_symbol, account_id)
);
