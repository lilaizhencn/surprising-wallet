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
    'BTC', 'testnet3', 'bitcoin-like', 1, 0, 'BTC',
    null, 'https://mempool.space/testnet/tx/',
    1, 6, 2, 546, true
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
    'BTC', 'mainnet', 'bitcoin-like', 1, 0, 'BTC',
    null, 'https://mempool.space/tx/',
    1, 6, 2, 546, true
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
values ('BTC', 'BTC', 'NATIVE', 8, true, true, 546, 546)
on conflict (chain, symbol) do update
set decimals = excluded.decimals,
    native_asset = excluded.native_asset,
    active = excluded.active,
    min_transfer = excluded.min_transfer,
    min_withdraw = excluded.min_withdraw,
    updated_at = now();

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
    'DOGE', 'regtest', 'bitcoin-like', 41, 3, 'DOGE',
    'http://127.0.0.1:22555', null,
    6, 6, 1000, 1000000, true
)
on conflict (chain, network) do update
set family = excluded.family,
    runtime_currency_id = excluded.runtime_currency_id,
    bip44_coin_type = excluded.bip44_coin_type,
    native_symbol = excluded.native_symbol,
    rpc_url = excluded.rpc_url,
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
create table if not exists ltc_withdraw_record (like btc_withdraw_record including defaults including identity);
create table if not exists ltc_withdraw_transaction (like btc_withdraw_transaction including defaults including identity);
create table if not exists doge_address (like btc_address including defaults including identity);
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

create index if not exists idx_ltc_withdraw_record_tx_id
    on ltc_withdraw_record (tx_id);
create index if not exists idx_ltc_withdraw_record_status
    on ltc_withdraw_record (status);
create index if not exists idx_ltc_withdraw_transaction_tx_id
    on ltc_withdraw_transaction (tx_id);
create index if not exists idx_ltc_withdraw_transaction_status
    on ltc_withdraw_transaction (status);
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

insert into chain_profile(chain,network,family,runtime_currency_id,bip44_coin_type,native_symbol,rpc_url,explorer_url,deposit_confirmations,withdraw_confirmations,default_fee_rate,dust_threshold,enabled)
values
('BCH','regtest','bitcoin-like',42,145,'BCH','http://127.0.0.1:18443',null,6,6,1,546,true),
('BCH','testnet','bitcoin-like',42,145,'BCH',null,'https://tbch.loping.net/tx/',1,6,1,546,true),
('BCH','mainnet','bitcoin-like',42,145,'BCH',null,'https://blockchair.com/bitcoin-cash/transaction/',1,6,1,546,true)
on conflict(chain,network) do update set runtime_currency_id=excluded.runtime_currency_id,bip44_coin_type=excluded.bip44_coin_type,explorer_url=excluded.explorer_url,deposit_confirmations=excluded.deposit_confirmations,withdraw_confirmations=excluded.withdraw_confirmations,default_fee_rate=excluded.default_fee_rate,dust_threshold=excluded.dust_threshold,enabled=excluded.enabled,updated_at=now();
insert into chain_asset(chain,symbol,asset_kind,decimals,native_asset,active,min_transfer,min_withdraw)
values('BCH','BCH','NATIVE',8,true,true,546,546)
on conflict(chain,symbol) do update set decimals=excluded.decimals,active=excluded.active,updated_at=now();
create table if not exists bch_address(like btc_address including defaults including identity);
create table if not exists bch_withdraw_record(like btc_withdraw_record including defaults including identity);
create table if not exists bch_withdraw_transaction(like btc_withdraw_transaction including defaults including identity);
do $$ begin
 if not exists(select 1 from pg_constraint where conname='pk_bch_address')then alter table bch_address add constraint pk_bch_address primary key(id);end if;
 if not exists(select 1 from pg_constraint where conname='uq_bch_address_address')then alter table bch_address add constraint uq_bch_address_address unique(address);end if;
 if not exists(select 1 from pg_constraint where conname='uq_bch_address_user_biz_index')then alter table bch_address add constraint uq_bch_address_user_biz_index unique(user_id,biz,index);end if;
 if not exists(select 1 from pg_constraint where conname='pk_bch_withdraw_record')then alter table bch_withdraw_record add constraint pk_bch_withdraw_record primary key(id);end if;
 if not exists(select 1 from pg_constraint where conname='uq_bch_withdraw_record_withdraw_id')then alter table bch_withdraw_record add constraint uq_bch_withdraw_record_withdraw_id unique(withdraw_id);end if;
 if not exists(select 1 from pg_constraint where conname='pk_bch_withdraw_transaction')then alter table bch_withdraw_transaction add constraint pk_bch_withdraw_transaction primary key(id);end if;
end $$;
insert into best_block_height(currency,height,interval_time)values(42,0,600000)on conflict(currency)do nothing;
insert into currency_balance(currency_index,balance)values(42,0)on conflict(currency_index)do nothing;

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

create table if not exists aptos_transaction (
    id bigserial primary key,
    chain varchar(32) not null default 'APTOS',
    tx_hash varchar(128) not null,
    sender varchar(128),
    receiver varchar(128) not null,
    asset_symbol varchar(32) not null,
    coin_type varchar(256),
    amount numeric(78, 18) not null default 0,
    gas_used bigint not null default 0,
    gas_unit_price bigint not null default 0,
    version bigint,
    sequence_number bigint,
    confirmations integer not null default 0,
    status varchar(32) not null default 'CREATED',
    raw_payload text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, tx_hash)
);

create table if not exists sui_transaction (
    id bigserial primary key,
    chain varchar(32) not null default 'SUI',
    tx_digest varchar(128) not null,
    sender varchar(128),
    receiver varchar(128) not null,
    asset_symbol varchar(32) not null,
    coin_type varchar(256),
    amount numeric(78, 18) not null default 0,
    gas_used bigint not null default 0,
    checkpoint bigint,
    status varchar(32) not null default 'CREATED',
    raw_payload text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, tx_digest)
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

-- Unified address registry for account/object chains. Private key material is
-- never persisted here; derivation_path is safe metadata and the master seed
-- remains in external secret storage.
create table if not exists chain_address (
    id bigserial primary key,
    chain varchar(32) not null,
    asset_symbol varchar(32) not null,
    account_id varchar(160) not null,
    user_id bigint not null,
    biz integer not null default 0,
    address_index bigint not null,
    address varchar(160) not null,
    owner_address varchar(160),
    derivation_path varchar(96) not null,
    wallet_role varchar(32) not null default 'DEPOSIT',
    enabled boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, asset_symbol, address),
    unique (chain, asset_symbol, user_id, biz, address_index, wallet_role)
);

create index if not exists idx_chain_address_scan
    on chain_address(chain, asset_symbol, enabled);
create index if not exists idx_chain_address_owner
    on chain_address(chain, owner_address);

insert into chain_profile(
    chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
    rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
    default_fee_rate, dust_threshold, enabled
)
values
    ('SOLANA', 'devnet', 'solana', 50, 501, 'SOL',
     'https://api.devnet.solana.com', 'https://explorer.solana.com/tx/',
     1, 1, 5000, 890880, true),
    ('SOLANA', 'mainnet', 'solana', 50, 501, 'SOL',
     null, 'https://explorer.solana.com/tx/',
     32, 32, 5000, 890880, false)
on conflict (chain, network) do update
set family = excluded.family,
    runtime_currency_id = excluded.runtime_currency_id,
    bip44_coin_type = excluded.bip44_coin_type,
    native_symbol = excluded.native_symbol,
    rpc_url = coalesce(excluded.rpc_url, chain_profile.rpc_url),
    explorer_url = excluded.explorer_url,
    deposit_confirmations = excluded.deposit_confirmations,
    withdraw_confirmations = excluded.withdraw_confirmations,
    default_fee_rate = excluded.default_fee_rate,
    dust_threshold = excluded.dust_threshold,
    enabled = excluded.enabled,
    updated_at = now();

insert into chain_asset(
    chain, symbol, asset_kind, decimals, native_asset, active, min_transfer, min_withdraw
)
values ('SOLANA', 'SOL', 'NATIVE', 9, true, true, 1, 1)
on conflict (chain, symbol) do update
set asset_kind = excluded.asset_kind,
    decimals = excluded.decimals,
    native_asset = excluded.native_asset,
    active = excluded.active,
    min_transfer = excluded.min_transfer,
    min_withdraw = excluded.min_withdraw,
    updated_at = now();

create table if not exists account_sequence (
    id bigserial primary key,
    chain varchar(32) not null,
    address varchar(160) not null,
    chain_sequence bigint not null default 0,
    next_sequence bigint not null default 0,
    status varchar(32) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (chain, address)
);

insert into chain_profile(
    chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
    rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
    default_fee_rate, dust_threshold, enabled
)
values
    ('TON', 'testnet', 'ton', 51, 607, 'TON',
     'https://testnet.toncenter.com/api/v2', 'https://testnet.tonviewer.com/transaction/',
     1, 1, 5000000, 1000000, true),
    ('TON', 'mainnet', 'ton', 51, 607, 'TON',
     null, 'https://tonviewer.com/transaction/',
     1, 1, 5000000, 1000000, false)
on conflict (chain, network) do update
set family = excluded.family,
    runtime_currency_id = excluded.runtime_currency_id,
    bip44_coin_type = excluded.bip44_coin_type,
    native_symbol = excluded.native_symbol,
    rpc_url = coalesce(excluded.rpc_url, chain_profile.rpc_url),
    explorer_url = excluded.explorer_url,
    deposit_confirmations = excluded.deposit_confirmations,
    withdraw_confirmations = excluded.withdraw_confirmations,
    default_fee_rate = excluded.default_fee_rate,
    dust_threshold = excluded.dust_threshold,
    enabled = excluded.enabled,
    updated_at = now();

insert into chain_asset(
    chain, symbol, asset_kind, decimals, native_asset, active, min_transfer, min_withdraw
)
values ('TON', 'TON', 'NATIVE', 9, true, true, 1000000, 1000000)
on conflict (chain, symbol) do update
set asset_kind = excluded.asset_kind,
    decimals = excluded.decimals,
    native_asset = excluded.native_asset,
    active = excluded.active,
    min_transfer = excluded.min_transfer,
    min_withdraw = excluded.min_withdraw,
    updated_at = now();

insert into token_config(
    chain, network, symbol, standard, token_standard, contract_address,
    decimals, enabled, min_deposit, min_withdraw, min_deposit_amount,
    min_withdraw_amount, collect_enabled, collect_threshold, gas_strategy,
    confirmation_required
)
values
    ('TON', 'testnet', 'USDT', 'JETTON', 'JETTON', 'TON_TESTNET_USDT_JETTON_MASTER_CONFIGURE_IN_DB',
     6, false, 1, 1, 1, 1, true, 1, 'TON_FORWARD_FEE', 1),
    ('TON', 'testnet', 'USDC', 'JETTON', 'JETTON', 'TON_TESTNET_USDC_JETTON_MASTER_CONFIGURE_IN_DB',
     6, false, 1, 1, 1, 1, true, 1, 'TON_FORWARD_FEE', 1)
on conflict (chain, symbol) do update
set network = excluded.network,
    standard = excluded.standard,
    token_standard = excluded.token_standard,
    contract_address = case
        when token_config.enabled then token_config.contract_address
        else excluded.contract_address
    end,
    decimals = excluded.decimals,
    enabled = token_config.enabled,
    min_deposit = excluded.min_deposit,
    min_withdraw = excluded.min_withdraw,
    min_deposit_amount = excluded.min_deposit_amount,
    min_withdraw_amount = excluded.min_withdraw_amount,
    collect_enabled = excluded.collect_enabled,
    collect_threshold = excluded.collect_threshold,
    gas_strategy = excluded.gas_strategy,
    confirmation_required = excluded.confirmation_required,
    updated_at = now();

insert into chain_profile(
    chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
    rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
    default_fee_rate, dust_threshold, enabled
)
values
    ('SUI', 'testnet', 'sui', 53, 784, 'SUI',
     'https://fullnode.testnet.sui.io:443', 'https://suiexplorer.com/txblock/',
     1, 1, 10000000, 1, true),
    ('SUI', 'mainnet', 'sui', 53, 784, 'SUI',
     null, 'https://suiexplorer.com/txblock/',
     1, 1, 10000000, 1, false)
on conflict (chain, network) do update
set family = excluded.family,
    runtime_currency_id = excluded.runtime_currency_id,
    bip44_coin_type = excluded.bip44_coin_type,
    native_symbol = excluded.native_symbol,
    rpc_url = coalesce(excluded.rpc_url, chain_profile.rpc_url),
    explorer_url = excluded.explorer_url,
    deposit_confirmations = excluded.deposit_confirmations,
    withdraw_confirmations = excluded.withdraw_confirmations,
    default_fee_rate = excluded.default_fee_rate,
    dust_threshold = excluded.dust_threshold,
    enabled = excluded.enabled,
    updated_at = now();

insert into chain_asset(
    chain, symbol, asset_kind, decimals, native_asset, active, min_transfer, min_withdraw
)
values ('SUI', 'SUI', 'NATIVE', 9, true, true, 1, 1)
on conflict (chain, symbol) do update
set asset_kind = excluded.asset_kind,
    decimals = excluded.decimals,
    native_asset = excluded.native_asset,
    active = excluded.active,
    min_transfer = excluded.min_transfer,
    min_withdraw = excluded.min_withdraw,
    updated_at = now();

insert into token_config(
    chain, network, symbol, standard, token_standard, contract_address,
    decimals, enabled, min_deposit, min_withdraw, min_deposit_amount,
    min_withdraw_amount, collect_enabled, collect_threshold, gas_strategy,
    confirmation_required
)
values
    ('SUI', 'testnet', 'TESTCOIN', 'SUI_COIN', 'COIN',
     '0x2::sui::SUI', 9, false, 1, 1, 1, 1, true, 1, 'SUI_GAS_OBJECT', 1)
on conflict (chain, symbol) do update
set network = excluded.network,
    standard = excluded.standard,
    token_standard = excluded.token_standard,
    contract_address = case
        when token_config.enabled then token_config.contract_address
        else excluded.contract_address
    end,
    decimals = excluded.decimals,
    enabled = token_config.enabled,
    min_deposit = excluded.min_deposit,
    min_withdraw = excluded.min_withdraw,
    min_deposit_amount = excluded.min_deposit_amount,
    min_withdraw_amount = excluded.min_withdraw_amount,
    collect_enabled = excluded.collect_enabled,
    collect_threshold = excluded.collect_threshold,
    gas_strategy = excluded.gas_strategy,
    confirmation_required = excluded.confirmation_required,
    updated_at = now();

insert into chain_profile(
    chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
    rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
    default_fee_rate, dust_threshold, enabled
)
values
    ('APTOS', 'devnet', 'aptos', 52, 637, 'APT',
     'https://fullnode.devnet.aptoslabs.com/v1', 'https://explorer.aptoslabs.com/txn/',
     1, 1, 5000000, 1, true),
    ('APTOS', 'testnet', 'aptos', 52, 637, 'APT',
     'https://fullnode.testnet.aptoslabs.com/v1', 'https://explorer.aptoslabs.com/txn/',
     1, 1, 5000000, 1, false),
    ('APTOS', 'mainnet', 'aptos', 52, 637, 'APT',
     null, 'https://explorer.aptoslabs.com/txn/',
     1, 1, 5000000, 1, false)
on conflict (chain, network) do update
set family = excluded.family,
    runtime_currency_id = excluded.runtime_currency_id,
    bip44_coin_type = excluded.bip44_coin_type,
    native_symbol = excluded.native_symbol,
    rpc_url = coalesce(excluded.rpc_url, chain_profile.rpc_url),
    explorer_url = excluded.explorer_url,
    deposit_confirmations = excluded.deposit_confirmations,
    withdraw_confirmations = excluded.withdraw_confirmations,
    default_fee_rate = excluded.default_fee_rate,
    dust_threshold = excluded.dust_threshold,
    enabled = excluded.enabled,
    updated_at = now();

insert into chain_asset(
    chain, symbol, asset_kind, decimals, native_asset, active, min_transfer, min_withdraw
)
values ('APTOS', 'APT', 'NATIVE', 8, true, true, 1, 1)
on conflict (chain, symbol) do update
set asset_kind = excluded.asset_kind,
    decimals = excluded.decimals,
    native_asset = excluded.native_asset,
    active = excluded.active,
    min_transfer = excluded.min_transfer,
    min_withdraw = excluded.min_withdraw,
    updated_at = now();

insert into token_config(
    chain, network, symbol, standard, token_standard, contract_address,
    decimals, enabled, min_deposit, min_withdraw, min_deposit_amount,
    min_withdraw_amount, collect_enabled, collect_threshold, gas_strategy,
    confirmation_required
)
values
    ('APTOS', 'devnet', 'TESTCOIN', 'APTOS_COIN', 'COIN',
     '0x1::aptos_coin::AptosCoin', 8, false, 1, 1, 1, 1, true, 1, 'APT_GAS', 1)
on conflict (chain, symbol) do update
set network = excluded.network,
    standard = excluded.standard,
    token_standard = excluded.token_standard,
    contract_address = case
        when token_config.enabled then token_config.contract_address
        else excluded.contract_address
    end,
    decimals = excluded.decimals,
    enabled = token_config.enabled,
    min_deposit = excluded.min_deposit,
    min_withdraw = excluded.min_withdraw,
    min_deposit_amount = excluded.min_deposit_amount,
    min_withdraw_amount = excluded.min_withdraw_amount,
    collect_enabled = excluded.collect_enabled,
    collect_threshold = excluded.collect_threshold,
    gas_strategy = excluded.gas_strategy,
    confirmation_required = excluded.confirmation_required,
    updated_at = now();
