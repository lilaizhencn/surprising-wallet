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
    updated_at timestamptz not null default now(),
    unique (order_no)
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
