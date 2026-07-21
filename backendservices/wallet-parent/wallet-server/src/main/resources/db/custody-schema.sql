-- Surprising Wallet multi-tenant custody schema.
-- This script is intentionally additive and idempotent. Existing chain configuration,
-- funded test addresses and seed material are not altered.

CREATE TABLE IF NOT EXISTS wallet_key_config (
    id smallint PRIMARY KEY,
    sig1_seed text NOT NULL,
    sig2_seed text NOT NULL,
    recovery_seed text NOT NULL,
    ed25519_seed text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(128) NOT NULL,
    CONSTRAINT wallet_key_config_singleton_check CHECK (id = 1)
);

CREATE UNIQUE INDEX IF NOT EXISTS chain_profile_one_enabled_network_idx
    ON chain_profile (upper(chain)) WHERE enabled = true;

-- Token contracts are network-scoped. Preserve testnet and mainnet contracts side by side,
-- while allowing only one enabled contract per chain/symbol at runtime.
UPDATE token_config token
   SET network = profile.network,
       updated_at = now()
  FROM chain_profile profile
 WHERE upper(profile.chain) = upper(token.chain)
   AND profile.enabled = true
   AND coalesce(trim(token.network), '') = '';

ALTER TABLE token_config DROP CONSTRAINT IF EXISTS token_config_chain_symbol_key;
ALTER TABLE token_config DROP CONSTRAINT IF EXISTS token_config_chain_contract_address_key;

CREATE UNIQUE INDEX IF NOT EXISTS token_config_chain_network_symbol_key
    ON token_config (chain, network, symbol);

CREATE UNIQUE INDEX IF NOT EXISTS token_config_chain_network_contract_address_key
    ON token_config (chain, network, contract_address);

CREATE UNIQUE INDEX IF NOT EXISTS token_config_one_enabled_network_per_asset_idx
    ON token_config (upper(chain), upper(symbol)) WHERE enabled = true;

-- Official issuer references (checked 2026-07-21):
-- Circle USDC: https://developers.circle.com/stablecoins/usdc-contract-addresses
-- Tether USDt: https://tether.to/en/supported-protocols/
-- USDT0 deployments: https://docs.usdt0.to/technical-documentation/deployments
-- Mainnet contracts are configuration-only and remain disabled until the matching chain,
-- audited RPC nodes and wallet tasks are deliberately enabled.
WITH mainnet_stablecoins(chain, symbol, standard, token_standard, contract_address,
                         contract_address_base58, contract_address_hex, decimals, gas_strategy) AS (
    VALUES
        ('APTOS', 'USDC', 'APTOS_FA', 'APTOS_FA',
         '0xbae207659db88bea0cbead6da0ed00aac12edcdda169e591cd41c94180b46f3b', NULL, NULL, 6, 'APT_GAS'),
        ('APTOS', 'USDT', 'APTOS_FA', 'APTOS_FA',
         '0x357b0b74bc833e95a115ad22604854d6b0fca151cecd94111770e5d6ffc9dc2b', NULL, NULL, 6, 'APT_GAS'),
        ('ARBITRUM', 'USDC', 'ERC20', 'ERC20',
         '0xaf88d065e77c8cC2239327C5EDb3A432268e5831', NULL, NULL, 6, 'native-gas'),
        ('ARBITRUM', 'USDT', 'ERC20', 'ERC20',
         '0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9', NULL, NULL, 6, 'native-gas'),
        ('AVAX_C', 'USDC', 'ERC20', 'ERC20',
         '0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E', NULL, NULL, 6, 'native-gas'),
        ('AVAX_C', 'USDT', 'ERC20', 'ERC20',
         '0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7', NULL, NULL, 6, 'native-gas'),
        ('BASE', 'USDC', 'ERC20', 'ERC20',
         '0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913', NULL, NULL, 6, 'native-gas'),
        ('DOT', 'USDC', 'ASSET_HUB_ASSET', 'ASSET_HUB_ASSET',
         '1337', NULL, NULL, 6, 'ASSET_HUB_NATIVE_GAS'),
        ('DOT', 'USDT', 'ASSET_HUB_ASSET', 'ASSET_HUB_ASSET',
         '1984', NULL, NULL, 6, 'ASSET_HUB_NATIVE_GAS'),
        ('ETH', 'USDC', 'ERC20', 'ERC20',
         '0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48', NULL, NULL, 6, 'native-gas'),
        ('ETH', 'USDT', 'ERC20', 'ERC20',
         '0xdAC17F958D2ee523a2206206994597C13D831ec7', NULL, NULL, 6, 'native-gas'),
        ('HYPEREVM', 'USDC', 'ERC20', 'ERC20',
         '0xb88339CB7199b77E23DB6E890353E22632Ba630f', NULL, NULL, 6, 'native-gas'),
        ('HYPEREVM', 'USDT', 'ERC20', 'ERC20',
         '0xB8CE59FC3717ada4C02eaDF9682A9e934F625ebb', NULL, NULL, 6, 'native-gas'),
        ('LINEA', 'USDC', 'ERC20', 'ERC20',
         '0x176211869cA2b568f2A7D4EE941E073a821EE1ff', NULL, NULL, 6, 'native-gas'),
        ('MANTLE', 'USDT', 'ERC20', 'ERC20',
         '0x779Ded0c9e1022225f8E0630b35a9b54bE713736', NULL, NULL, 6, 'native-gas'),
        ('NEAR', 'USDC', 'NEP141', 'NEP141',
         '17208628f84f5d6ad33f0da3bbbeb27ffcb398eac501a31bd6ad2011e36133a1', NULL, NULL, 6, 'NEAR_STORAGE_DEPOSIT'),
        ('NEAR', 'USDT', 'NEP141', 'NEP141',
         'usdt.tether-token.near', NULL, NULL, 6, 'NEAR_STORAGE_DEPOSIT'),
        ('OPTIMISM', 'USDC', 'ERC20', 'ERC20',
         '0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85', NULL, NULL, 6, 'native-gas'),
        ('OPTIMISM', 'USDT', 'ERC20', 'ERC20',
         '0x01bFF41798a0BcF287b996046Ca68b395DbC1071', NULL, NULL, 6, 'native-gas'),
        ('POLYGON', 'USDC', 'ERC20', 'ERC20',
         '0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359', NULL, NULL, 6, 'native-gas'),
        ('POLYGON', 'USDT', 'ERC20', 'ERC20',
         '0xc2132D05D31c914a87C6611C10748AEb04B58e8F', NULL, NULL, 6, 'native-gas'),
        ('SOLANA', 'USDC', 'SPL', 'SPL',
         'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v',
         'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v', NULL, 6, 'SOL_FEE_PAYER'),
        ('SOLANA', 'USDT', 'SPL', 'SPL',
         'Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB',
         'Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB', NULL, 6, 'SOL_FEE_PAYER'),
        ('SUI', 'USDC', 'SUI_COIN', 'COIN',
         '0xdba34672e30cb065b1f93e3ab55318768fd6fef66c15942c9f7cb846e2f900e7::usdc::USDC', NULL, NULL, 6, 'SUI_GAS_OBJECT'),
        ('TON', 'USDT', 'JETTON', 'JETTON',
         'EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs', NULL, NULL, 6, 'TON_FORWARD_FEE'),
        ('TRON', 'USDT', 'TRC20', 'TRC20',
         'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t',
         'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t',
         '41a614f803b6fd780986a42c78ec9c7f77e6ded13c', 6, 'energy-bandwidth'),
        ('UNICHAIN', 'USDC', 'ERC20', 'ERC20',
         '0x078D782b760474a361dDA0AF3839290b0EF57AD6', NULL, NULL, 6, 'native-gas'),
        ('UNICHAIN', 'USDT', 'ERC20', 'ERC20',
         '0x9151434b16b9763660705744891fA906F660EcC5', NULL, NULL, 6, 'native-gas'),
        ('XRP', 'USDC', 'XRPL_ISSUED', 'ISSUED_CURRENCY',
         'rGm7WCVp9gb4jZHWTEtGUr4dd74z2XuWhE:5553444300000000000000000000000000000000',
         NULL, '5553444300000000000000000000000000000000', 6, 'XRP_TRUSTLINE')
)
INSERT INTO token_config(
    chain, network, symbol, standard, token_standard, contract_address,
    contract_address_base58, contract_address_hex, decimals, enabled,
    min_deposit, min_withdraw, min_deposit_amount, min_withdraw_amount,
    collect_enabled, collect_threshold, gas_strategy, confirmation_required, updated_at)
SELECT stablecoin.chain, 'mainnet', stablecoin.symbol, stablecoin.standard,
       stablecoin.token_standard, stablecoin.contract_address,
       stablecoin.contract_address_base58, stablecoin.contract_address_hex,
       stablecoin.decimals, false, 1, 1, 1, 1, false, 1,
       stablecoin.gas_strategy, profile.deposit_confirmations, now()
  FROM mainnet_stablecoins stablecoin
  JOIN chain_profile profile
    ON profile.chain = stablecoin.chain AND profile.network = 'mainnet'
 WHERE NOT EXISTS (
       SELECT 1
         FROM token_config existing
        WHERE existing.chain = stablecoin.chain
          AND existing.network = 'mainnet'
          AND existing.symbol = stablecoin.symbol
 );

-- Mantle's canonical USDT moved to USDT0. Correct only the known disabled legacy
-- template; never rewrite an enabled production token contract during startup.
UPDATE token_config
   SET contract_address = '0x779Ded0c9e1022225f8E0630b35a9b54bE713736',
       updated_at = now()
 WHERE chain = 'MANTLE'
   AND network = 'mainnet'
   AND symbol = 'USDT'
   AND lower(contract_address) = lower('0x201EBa5CC46D216Ce6DC03F6a759e8E766e956aE')
   AND enabled = false;

UPDATE token_config
   SET collect_enabled = false,
       updated_at = now()
 WHERE network = 'mainnet'
   AND symbol IN ('USDC', 'USDT')
   AND enabled = false
   AND collect_enabled = true;

ALTER TABLE chain_rpc_node ADD COLUMN IF NOT EXISTS last_checked_at timestamptz;
ALTER TABLE chain_rpc_node ADD COLUMN IF NOT EXISTS last_latency_ms bigint;
ALTER TABLE chain_rpc_node ADD COLUMN IF NOT EXISTS last_http_status integer;
ALTER TABLE chain_rpc_node ADD COLUMN IF NOT EXISTS last_error text;

CREATE SEQUENCE IF NOT EXISTS custody_derivation_namespace_seq
    AS integer START WITH 1000 INCREMENT BY 1 MINVALUE 1000 MAXVALUE 2147483646 CACHE 16;

CREATE SEQUENCE IF NOT EXISTS custody_derivation_subject_index_seq
    AS integer START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483646 CACHE 64;

CREATE TABLE IF NOT EXISTS custody_tenant (
    id uuid PRIMARY KEY,
    slug varchar(64) NOT NULL,
    name varchar(160) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    derivation_namespace integer NOT NULL DEFAULT nextval('custody_derivation_namespace_seq'),
    ip_allowlist_enabled boolean NOT NULL DEFAULT false,
    display_currency varchar(12) NOT NULL DEFAULT 'USD',
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custody_tenant_slug_key UNIQUE (slug),
    CONSTRAINT custody_tenant_derivation_namespace_key UNIQUE (derivation_namespace),
    CONSTRAINT custody_tenant_slug_check CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$'),
    CONSTRAINT custody_tenant_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE TABLE IF NOT EXISTS custody_derivation_subject (
    tenant_id uuid NOT NULL REFERENCES custody_tenant(id) ON DELETE RESTRICT,
    subject varchar(160) NOT NULL,
    derivation_subject integer NOT NULL DEFAULT nextval('custody_derivation_subject_index_seq'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, subject),
    CONSTRAINT custody_derivation_subject_path_key UNIQUE (derivation_subject),
    CONSTRAINT custody_derivation_subject_value_check
        CHECK (subject ~ '^[A-Za-z0-9_][A-Za-z0-9._:-]{0,159}$')
);

CREATE TABLE IF NOT EXISTS custody_tenant_user (
    id uuid PRIMARY KEY,
    tenant_id uuid REFERENCES custody_tenant(id),
    email varchar(254) NOT NULL,
    display_name varchar(120) NOT NULL,
    password_hash varchar(512) NOT NULL,
    role varchar(32) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    failed_login_count integer NOT NULL DEFAULT 0,
    locked_until timestamptz,
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custody_tenant_user_role_check
        CHECK (role IN ('PLATFORM_ADMIN', 'TENANT_ADMIN', 'OPERATOR', 'VIEWER')),
    CONSTRAINT custody_tenant_user_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT custody_tenant_user_scope_check
        CHECK ((role = 'PLATFORM_ADMIN' AND tenant_id IS NULL)
            OR (role <> 'PLATFORM_ADMIN' AND tenant_id IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS custody_tenant_user_email_key
    ON custody_tenant_user (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(email));

CREATE TABLE IF NOT EXISTS custody_session (
    id uuid PRIMARY KEY,
    tenant_user_id uuid NOT NULL REFERENCES custody_tenant_user(id) ON DELETE CASCADE,
    tenant_id uuid REFERENCES custody_tenant(id) ON DELETE CASCADE,
    token_hash varchar(128) NOT NULL,
    source_ip inet,
    user_agent varchar(512),
    expires_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custody_session_token_hash_key UNIQUE (token_hash)
);

CREATE INDEX IF NOT EXISTS custody_session_active_idx
    ON custody_session (token_hash, expires_at) WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS custody_api_key (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES custody_tenant(id) ON DELETE CASCADE,
    key_id varchar(64) NOT NULL,
    name varchar(120) NOT NULL,
    secret_ciphertext text NOT NULL,
    secret_version integer NOT NULL DEFAULT 1,
    scopes text[] NOT NULL DEFAULT ARRAY[]::text[],
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    last_used_at timestamptz,
    last_used_ip inet,
    expires_at timestamptz,
    created_by uuid REFERENCES custody_tenant_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    CONSTRAINT custody_api_key_key_id_key UNIQUE (key_id),
    CONSTRAINT custody_api_key_status_check CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE INDEX IF NOT EXISTS custody_api_key_tenant_idx
    ON custody_api_key (tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS custody_api_nonce (
    key_id varchar(64) NOT NULL REFERENCES custody_api_key(key_id) ON DELETE CASCADE,
    nonce varchar(128) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (key_id, nonce)
);

CREATE INDEX IF NOT EXISTS custody_api_nonce_expiry_idx ON custody_api_nonce (expires_at);

CREATE TABLE IF NOT EXISTS custody_ip_rule (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES custody_tenant(id) ON DELETE CASCADE,
    label varchar(120) NOT NULL,
    cidr inet NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_by uuid REFERENCES custody_tenant_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custody_ip_rule_tenant_cidr_key UNIQUE (tenant_id, cidr)
);

CREATE INDEX IF NOT EXISTS custody_ip_rule_enabled_idx
    ON custody_ip_rule (tenant_id, enabled);

CREATE TABLE IF NOT EXISTS custody_webhook_endpoint (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES custody_tenant(id) ON DELETE CASCADE,
    name varchar(120) NOT NULL,
    url varchar(2048) NOT NULL,
    secret_ciphertext text NOT NULL,
    secret_version integer NOT NULL DEFAULT 1,
    subscribed_events text[] NOT NULL DEFAULT ARRAY[]::text[],
    status varchar(24) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    verification_token_hash varchar(128),
    verified_at timestamptz,
    last_delivery_at timestamptz,
    created_by uuid REFERENCES custody_tenant_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custody_webhook_endpoint_tenant_url_key UNIQUE (tenant_id, url),
    CONSTRAINT custody_webhook_endpoint_status_check
        CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED'))
);

CREATE INDEX IF NOT EXISTS custody_webhook_endpoint_active_idx
    ON custody_webhook_endpoint (tenant_id, status);

CREATE TABLE IF NOT EXISTS custody_address (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES custody_tenant(id) ON DELETE RESTRICT,
    chain_address_id bigint NOT NULL REFERENCES chain_address(id) ON DELETE RESTRICT,
    chain varchar(32) NOT NULL,
    network varchar(64) NOT NULL,
    address varchar(160) NOT NULL,
    memo varchar(160),
    subject varchar(160) NOT NULL,
    label varchar(160),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    source varchar(24) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    derivation_subject integer NOT NULL,
    derivation_child bigint NOT NULL,
    created_by uuid REFERENCES custody_tenant_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custody_address_source_check CHECK (source IN ('API', 'CONSOLE')),
    CONSTRAINT custody_address_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT custody_address_chain_address_key UNIQUE (chain_address_id),
    CONSTRAINT custody_address_derivation_key
        UNIQUE (tenant_id, chain, derivation_subject, derivation_child)
);

-- Upgrade the local pre-release schema without deleting existing address records.
ALTER TABLE custody_address ADD COLUMN IF NOT EXISTS subject varchar(160);
ALTER TABLE custody_address ADD COLUMN IF NOT EXISTS derivation_child bigint;
UPDATE custody_address
   SET subject = 'legacy:' || id::text
 WHERE subject IS NULL;
UPDATE custody_address c
   SET derivation_child = a.address_index
  FROM chain_address a
 WHERE a.id = c.chain_address_id
   AND c.derivation_child IS NULL;
ALTER TABLE custody_address ALTER COLUMN subject SET NOT NULL;
ALTER TABLE custody_address ALTER COLUMN derivation_child SET NOT NULL;
ALTER TABLE custody_address DROP CONSTRAINT IF EXISTS custody_address_derivation_subject_key;
DROP INDEX IF EXISTS custody_address_allocation_key;
CREATE UNIQUE INDEX IF NOT EXISTS custody_address_derivation_key
    ON custody_address (tenant_id, chain, derivation_subject, derivation_child);
INSERT INTO custody_derivation_subject(tenant_id, subject, derivation_subject)
SELECT tenant_id, subject, derivation_subject
  FROM custody_address
ON CONFLICT DO NOTHING;

CREATE INDEX IF NOT EXISTS custody_address_tenant_created_idx
    ON custody_address (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS custody_address_tenant_subject_idx
    ON custody_address (tenant_id, subject);
CREATE INDEX IF NOT EXISTS custody_address_lookup_idx
    ON custody_address (chain, lower(address), status);

CREATE TABLE IF NOT EXISTS custody_gas_account (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES custody_tenant(id) ON DELETE RESTRICT,
    custody_address_id uuid NOT NULL REFERENCES custody_address(id) ON DELETE RESTRICT,
    chain varchar(32) NOT NULL,
    network varchar(64) NOT NULL,
    native_symbol varchar(32) NOT NULL,
    low_balance_threshold numeric(78,24) NOT NULL DEFAULT 0,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    created_by uuid REFERENCES custody_tenant_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custody_gas_account_tenant_chain_key UNIQUE (tenant_id, chain),
    CONSTRAINT custody_gas_account_address_key UNIQUE (custody_address_id),
    CONSTRAINT custody_gas_account_threshold_check CHECK (low_balance_threshold >= 0),
    CONSTRAINT custody_gas_account_status_check CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX IF NOT EXISTS custody_gas_account_tenant_status_idx
    ON custody_gas_account (tenant_id, status);

CREATE TABLE IF NOT EXISTS custody_deposit (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES custody_tenant(id) ON DELETE RESTRICT,
    custody_address_id uuid NOT NULL REFERENCES custody_address(id) ON DELETE RESTRICT,
    deposit_record_id bigint NOT NULL REFERENCES deposit_record(id) ON DELETE RESTRICT,
    chain varchar(32) NOT NULL,
    asset_symbol varchar(32) NOT NULL,
    tx_hash varchar(128) NOT NULL,
    log_index bigint NOT NULL DEFAULT 0,
    amount numeric(78,24) NOT NULL,
    status varchar(32) NOT NULL,
    credited_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custody_deposit_tenant_record_key UNIQUE (tenant_id, deposit_record_id)
);

CREATE INDEX IF NOT EXISTS custody_deposit_tenant_time_idx
    ON custody_deposit (tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS custody_withdrawal (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES custody_tenant(id) ON DELETE RESTRICT,
    custody_address_id uuid NOT NULL REFERENCES custody_address(id) ON DELETE RESTRICT,
    order_no varchar(96) NOT NULL,
    external_reference varchar(160),
    idempotency_key varchar(160),
    chain varchar(32) NOT NULL,
    asset_symbol varchar(32) NOT NULL,
    to_address varchar(160) NOT NULL,
    amount numeric(78,24) NOT NULL,
    fee numeric(78,24) NOT NULL DEFAULT 0,
    status varchar(32) NOT NULL,
    created_by_type varchar(24) NOT NULL,
    created_by_id varchar(160),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custody_withdrawal_tenant_order_key UNIQUE (tenant_id, order_no),
    CONSTRAINT custody_withdrawal_creator_check CHECK (created_by_type IN ('API_KEY', 'CONSOLE'))
);

CREATE UNIQUE INDEX IF NOT EXISTS custody_withdrawal_idempotency_key
    ON custody_withdrawal (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS custody_withdrawal_tenant_time_idx
    ON custody_withdrawal (tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS custody_gas_usage (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES custody_tenant(id) ON DELETE RESTRICT,
    gas_account_id uuid NOT NULL REFERENCES custody_gas_account(id) ON DELETE RESTRICT,
    custody_withdrawal_id uuid NOT NULL REFERENCES custody_withdrawal(id) ON DELETE RESTRICT,
    order_no varchar(96) NOT NULL,
    chain varchar(32) NOT NULL,
    native_symbol varchar(32) NOT NULL,
    reserved_amount numeric(78,24) NOT NULL,
    actual_amount numeric(78,24),
    status varchar(24) NOT NULL DEFAULT 'RESERVED',
    pricing_source varchar(32) NOT NULL DEFAULT 'CONFIGURED_RESERVE',
    tx_hash varchar(128),
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    settled_at timestamptz,
    CONSTRAINT custody_gas_usage_withdrawal_key UNIQUE (custody_withdrawal_id),
    CONSTRAINT custody_gas_usage_tenant_order_key UNIQUE (tenant_id, order_no),
    CONSTRAINT custody_gas_usage_reserved_check CHECK (reserved_amount > 0),
    CONSTRAINT custody_gas_usage_actual_check CHECK (actual_amount IS NULL OR actual_amount >= 0),
    CONSTRAINT custody_gas_usage_status_check
        CHECK (status IN ('RESERVED', 'SETTLED', 'RELEASED', 'OVERDUE'))
);

CREATE INDEX IF NOT EXISTS custody_gas_usage_account_time_idx
    ON custody_gas_usage (gas_account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS custody_gas_usage_pending_idx
    ON custody_gas_usage (status, updated_at) WHERE status IN ('RESERVED', 'OVERDUE');

CREATE TABLE IF NOT EXISTS custody_ledger_entry (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES custody_tenant(id) ON DELETE RESTRICT,
    custody_address_id uuid REFERENCES custody_address(id) ON DELETE RESTRICT,
    chain varchar(32) NOT NULL,
    asset_symbol varchar(32) NOT NULL,
    account_id varchar(160) NOT NULL,
    entry_type varchar(32) NOT NULL,
    direction varchar(8) NOT NULL,
    amount numeric(78,24) NOT NULL,
    reference_type varchar(32) NOT NULL,
    reference_id varchar(192) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custody_ledger_entry_direction_check CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT custody_ledger_entry_amount_check CHECK (amount > 0),
    CONSTRAINT custody_ledger_entry_reference_key
        UNIQUE (tenant_id, entry_type, reference_type, reference_id)
);

CREATE INDEX IF NOT EXISTS custody_ledger_entry_tenant_asset_time_idx
    ON custody_ledger_entry (tenant_id, chain, asset_symbol, created_at DESC);

CREATE TABLE IF NOT EXISTS custody_event (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES custody_tenant(id) ON DELETE RESTRICT,
    event_type varchar(64) NOT NULL,
    aggregate_type varchar(32) NOT NULL,
    aggregate_id varchar(192) NOT NULL,
    payload jsonb NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custody_event_status_check CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT custody_event_business_key
        UNIQUE (tenant_id, event_type, aggregate_type, aggregate_id)
);

CREATE INDEX IF NOT EXISTS custody_event_pending_idx
    ON custody_event (status, created_at) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS custody_event_tenant_time_idx
    ON custody_event (tenant_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS custody_webhook_delivery (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES custody_tenant(id) ON DELETE RESTRICT,
    endpoint_id uuid NOT NULL REFERENCES custody_webhook_endpoint(id) ON DELETE CASCADE,
    event_id uuid NOT NULL REFERENCES custody_event(id) ON DELETE CASCADE,
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    locked_by varchar(160),
    locked_at timestamptz,
    last_http_status integer,
    last_error text,
    last_response text,
    delivered_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custody_webhook_delivery_event_key UNIQUE (endpoint_id, event_id),
    CONSTRAINT custody_webhook_delivery_status_check
        CHECK (status IN ('PENDING', 'DELIVERING', 'DELIVERED', 'RETRY', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS custody_webhook_delivery_due_idx
    ON custody_webhook_delivery (status, next_attempt_at)
    WHERE status IN ('PENDING', 'RETRY');
CREATE INDEX IF NOT EXISTS custody_webhook_delivery_tenant_time_idx
    ON custody_webhook_delivery (tenant_id, created_at DESC);

ALTER TABLE custody_webhook_delivery
    ADD COLUMN IF NOT EXISTS total_attempt_count integer NOT NULL DEFAULT 0;
ALTER TABLE custody_webhook_delivery
    ADD COLUMN IF NOT EXISTS manual_retry_count integer NOT NULL DEFAULT 0;
ALTER TABLE custody_webhook_delivery
    ADD COLUMN IF NOT EXISTS next_attempt_trigger varchar(24) NOT NULL DEFAULT 'AUTOMATIC';

CREATE TABLE IF NOT EXISTS custody_webhook_delivery_attempt (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES custody_tenant(id) ON DELETE RESTRICT,
    delivery_id uuid NOT NULL REFERENCES custody_webhook_delivery(id) ON DELETE CASCADE,
    attempt_number integer NOT NULL,
    retry_cycle integer NOT NULL DEFAULT 0,
    trigger varchar(24) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'IN_PROGRESS',
    worker_id varchar(160),
    http_status integer,
    error_message text,
    response_body text,
    next_attempt_at timestamptz,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    duration_ms bigint,
    CONSTRAINT custody_webhook_attempt_number_key UNIQUE (delivery_id, attempt_number),
    CONSTRAINT custody_webhook_attempt_trigger_check
        CHECK (trigger IN ('AUTOMATIC', 'MANUAL', 'RECOVERY')),
    CONSTRAINT custody_webhook_attempt_status_check
        CHECK (status IN ('IN_PROGRESS', 'DELIVERED', 'RETRY_SCHEDULED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS custody_webhook_attempt_delivery_time_idx
    ON custody_webhook_delivery_attempt (delivery_id, attempt_number DESC);
CREATE INDEX IF NOT EXISTS custody_webhook_attempt_tenant_time_idx
    ON custody_webhook_delivery_attempt (tenant_id, started_at DESC);

CREATE TABLE IF NOT EXISTS custody_idempotency_key (
    tenant_id uuid NOT NULL REFERENCES custody_tenant(id) ON DELETE CASCADE,
    idempotency_key varchar(160) NOT NULL,
    operation varchar(64) NOT NULL,
    request_hash varchar(128) NOT NULL,
    response_status integer,
    response_body jsonb,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idempotency_key, operation)
);

CREATE INDEX IF NOT EXISTS custody_idempotency_expiry_idx
    ON custody_idempotency_key (expires_at);

CREATE TABLE IF NOT EXISTS custody_audit_log (
    id uuid PRIMARY KEY,
    tenant_id uuid REFERENCES custody_tenant(id) ON DELETE RESTRICT,
    actor_type varchar(24) NOT NULL,
    actor_id varchar(160) NOT NULL,
    action varchar(96) NOT NULL,
    resource_type varchar(64) NOT NULL,
    resource_id varchar(192),
    source_ip inet,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custody_audit_log_actor_check
        CHECK (actor_type IN ('PLATFORM_USER', 'TENANT_USER', 'API_KEY', 'SYSTEM'))
);

CREATE INDEX IF NOT EXISTS custody_audit_log_tenant_time_idx
    ON custody_audit_log (tenant_id, created_at DESC);
