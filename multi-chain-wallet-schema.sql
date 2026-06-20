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

