package com.surprising.wallet.web.controller;

import com.surprising.commons.support.model.ResponseResult;
import com.surprising.commons.support.util.ResultUtils;
import com.surprising.wallet.common.chain.WalletPublicKey;
import com.surprising.wallet.service.wallet.HotWalletAddressService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * APIs consumed by the TokDou wallet operations page.
 */
@Slf4j
@RestController
@RequestMapping("/wallet/v1")
@CrossOrigin(
        origins = {"http://localhost:5173", "http://127.0.0.1:5173", "https://tokdou.com", "https://www.tokdou.com"},
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH, RequestMethod.OPTIONS}
)
public class WalletDashboardController {

    private static final int DEFAULT_LIMIT = 200;
    private static final Map<String, TableSpec> ADMIN_TABLES = adminTables();

    private final JdbcTemplate jdbcTemplate;
    private final HotWalletAddressService hotWalletAddressService;

    @Value("${SW_WALLET_ADMIN_USERNAME:${sw.wallet.admin.username:}}")
    private String adminUsername;

    @Value("${SW_WALLET_ADMIN_PASSWORD:${sw.wallet.admin.password:}}")
    private String adminPassword;

    @Value("${sw.wallet.ed25519.master-seed:}")
    private String ed25519MasterSeed;

    public WalletDashboardController(JdbcTemplate jdbcTemplate,
                                     HotWalletAddressService hotWalletAddressService) {
        this.jdbcTemplate = jdbcTemplate;
        this.hotWalletAddressService = hotWalletAddressService;
    }

    @GetMapping("/dashboard")
    public ResponseResult<Map<String, Object>> dashboard(
            @RequestParam(value = "limit", defaultValue = "200") Integer limit) {
        try {
            int rowLimit = normalizeLimit(limit);
            Map<String, Object> payload = orderedMap();
            payload.put("generatedAt", Instant.now().toString());
            payload.put("project", projectInfo());
            payload.put("api", apiInfo());
            payload.put("runtime", runtimeSnapshot(rowLimit));
            payload.put("documentation", documentation());
            payload.put("operations", operationsInfo());
            return ResultUtils.success(payload);
        } catch (Exception e) {
            log.error("wallet dashboard query failed", e);
            return ResultUtils.failure("查询钱包项目总览失败");
        }
    }

    @GetMapping("/dashboard/address-transactions")
    public ResponseResult<List<Map<String, Object>>> addressTransactions(
            @RequestParam("address") String address,
            @RequestParam(value = "chain", required = false) String chain,
            @RequestParam(value = "limit", defaultValue = "100") Integer limit) {
        try {
            List<Object> args = new ArrayList<>();
            String chainFilter = "";
            String chainValue = null;
            if (chain != null && !chain.isBlank()) {
                chainFilter = " and chain = ?";
                chainValue = chain.toUpperCase(Locale.ROOT);
            }
            addAddressQueryArgs(args, address, chainValue);
            addAddressQueryArgs(args, address, chainValue);
            addAddressQueryArgs(args, address, chainValue);
            args.add(normalizeLimit(limit));

            String sql = """
                    select * from (
                      select 'DEPOSIT' as event_type, id, chain, asset_symbol, tx_hash, from_address, to_address,
                             amount, null::numeric as fee, status, confirmations, block_height, credited, created_at, updated_at
                        from deposit_record
                       where (from_address = ? or to_address = ?) %s
                      union all
                      select 'WITHDRAW' as event_type, id, chain, asset_symbol, tx_hash, from_address, to_address,
                             amount, fee, status, null::integer as confirmations, null::bigint as block_height,
                             null::boolean as credited, created_at, updated_at
                        from withdrawal_order
                       where (from_address = ? or to_address = ?) %s
                      union all
                      select 'COLLECTION' as event_type, id, chain, asset_symbol, tx_hash, from_address, to_address,
                             amount, fee, status, null::integer as confirmations, null::bigint as block_height,
                             null::boolean as credited, created_at, updated_at
                        from collection_record
                       where (from_address = ? or to_address = ?) %s
                    ) t
                    order by updated_at desc
                    limit ?
                    """.formatted(chainFilter, chainFilter, chainFilter);
            return ResultUtils.success(queryRows(sql, args.toArray()));
        } catch (Exception e) {
            log.error("address transaction query failed address={} chain={}", address, chain, e);
            return ResultUtils.failure("查询地址交易记录失败");
        }
    }

    private static void addAddressQueryArgs(List<Object> args, String address, String chain) {
        args.add(address);
        args.add(address);
        if (chain != null) {
            args.add(chain);
        }
    }

    @PostMapping("/admin/login")
    public ResponseResult<Map<String, Object>> adminLogin(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return ResultUtils.success(adminPayload(DEFAULT_LIMIT));
    }

    @GetMapping("/admin/config")
    public ResponseResult<Map<String, Object>> adminConfig(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "limit", defaultValue = "200") Integer limit) {
        requireAdmin(authorization);
        return ResultUtils.success(adminPayload(normalizeLimit(limit)));
    }

    @GetMapping("/admin/config/{table}")
    public ResponseResult<Map<String, Object>> adminTable(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("table") String table,
            @RequestParam(value = "limit", defaultValue = "200") Integer limit) {
        requireAdmin(authorization);
        TableSpec spec = requireTable(table);
        Map<String, Object> payload = orderedMap();
        payload.put("table", table);
        payload.put("metadata", tableMetadata(table, spec));
        payload.put("columns", columns(table));
        payload.put("rows", tableRows(table, spec, normalizeLimit(limit)));
        return ResultUtils.success(payload);
    }

    @PatchMapping("/admin/config/{table}/{id}")
    public ResponseResult<Map<String, Object>> updateAdminRow(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("table") String table,
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        requireAdmin(authorization);
        TableSpec spec = requireTable(table);
        Map<String, Object> updates = normalizeAdminUpdates(table, extractUpdates(body));
        Map<String, String> columnTypes = columnTypes(table);
        List<String> assignments = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        Map<String, Object> effectiveUpdates = orderedMap();

        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String column = normalizeIdentifier(entry.getKey());
            if (!spec.editableColumns().contains(column)) {
                continue;
            }
            Object coercedValue = coerce(entry.getValue(), columnTypes.get(column));
            assignments.add(quote(column) + " = ?");
            args.add(coercedValue);
            effectiveUpdates.put(column, coercedValue);
        }

        if (assignments.isEmpty()) {
            return ResultUtils.failure("没有可更新的字段");
        }

        validateAdminUpdate(table, id, effectiveUpdates);

        if (columnTypes.containsKey("updated_at") && !updates.containsKey("updated_at")) {
            assignments.add("\"updated_at\" = now()");
        }

        args.add(coerce(id, columnTypes.get(spec.idColumn())));
        String sql = "update " + quote(table) + " set " + String.join(", ", assignments)
                + " where " + quote(spec.idColumn()) + " = ?";
        int updated = jdbcTemplate.update(sql, args.toArray());

        Map<String, Object> payload = orderedMap();
        payload.put("updated", updated);
        payload.put("table", table);
        payload.put("id", id);
        payload.put("row", tableRowsById(table, spec, id, columnTypes));
        return ResultUtils.success(payload);
    }

    private void validateAdminUpdate(String table, String id, Map<String, Object> updates) {
        if (!Objects.equals(table, "wallet_public_key")
                || (!updates.containsKey("public_key") && !updates.containsKey("enabled"))) {
            return;
        }
        try {
            hotWalletAddressService.requireCandidateWalletPublicKeysMatchDefaultHotAddresses(
                    walletPublicKeyCandidates(id, updates));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private List<WalletPublicKey> walletPublicKeyCandidates(String id, Map<String, Object> updates) {
        int targetSlot;
        try {
            targetSlot = Integer.parseInt(id);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "wallet_public_key id must be key_slot");
        }
        List<WalletPublicKey> rows = jdbcTemplate.query("""
                        select key_slot, key_role, key_type, network, public_key, enabled, remark
                          from wallet_public_key
                         order by key_slot
                        """,
                (rs, rowNum) -> WalletPublicKey.builder()
                        .keySlot(rs.getInt("key_slot"))
                        .keyRole(rs.getString("key_role"))
                        .keyType(rs.getString("key_type"))
                        .network(rs.getString("network"))
                        .publicKey(rs.getString("public_key"))
                        .enabled(rs.getBoolean("enabled"))
                        .remark(rs.getString("remark"))
                        .build());

        boolean found = false;
        List<WalletPublicKey> candidates = new ArrayList<>();
        for (WalletPublicKey row : rows) {
            if (row.getKeySlot() == targetSlot) {
                found = true;
                candidates.add(WalletPublicKey.builder()
                        .keySlot(row.getKeySlot())
                        .keyRole(row.getKeyRole())
                        .keyType(row.getKeyType())
                        .network(row.getNetwork())
                        .publicKey(updates.containsKey("public_key")
                                ? nullableString(updates.get("public_key"))
                                : row.getPublicKey())
                        .enabled(updates.containsKey("enabled")
                                ? parseBooleanFlag(updates.get("enabled"))
                                : row.getEnabled())
                        .remark(updates.containsKey("remark")
                                ? nullableString(updates.get("remark"))
                                : row.getRemark())
                        .build());
            } else {
                candidates.add(row);
            }
        }
        if (!found) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "wallet_public_key row not found for key_slot=" + id);
        }
        return candidates;
    }

    private String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> normalizeAdminUpdates(String table, Map<String, Object> updates) {
        Map<String, Object> normalized = orderedMap();
        updates.forEach(normalized::put);

        if (Objects.equals(table, "wallet_system_config")) {
            if (normalized.containsKey("enabled")) {
                boolean enabled = parseBooleanFlag(normalized.get("enabled"));
                normalized.put("enabled", enabled);
                normalized.put("config_value", String.valueOf(enabled));
                normalized.put("value_type", "boolean");
            } else {
                normalized.remove("config_value");
                normalized.remove("value_type");
            }
            return normalized;
        }

        if (Objects.equals(table, "chain_profile")) {
            if (normalized.containsKey("enabled") && !parseBooleanFlag(normalized.get("enabled"))) {
                normalized.put("scan_enabled", false);
                normalized.put("withdraw_enabled", false);
                normalized.put("collection_enabled", false);
                normalized.put("transfer_enabled", false);
            }
            return normalized;
        }

        if (Objects.equals(table, "token_config")) {
            if (normalized.containsKey("enabled") && !parseBooleanFlag(normalized.get("enabled"))) {
                normalized.put("collect_enabled", false);
            }
            return normalized;
        }

        if (Objects.equals(table, "chain_rpc_node")) {
            if (normalized.containsKey("auth_type")
                    && Objects.equals(String.valueOf(normalized.get("auth_type")).trim().toLowerCase(Locale.ROOT), "none")) {
                normalized.put("auth_header_name", null);
                normalized.put("api_key_ref", null);
                normalized.put("username_ref", null);
                normalized.put("password_ref", null);
            }
            return normalized;
        } else {
            return normalized;
        }
    }

    private boolean parseBooleanFlag(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return Objects.equals(text, "true")
                || Objects.equals(text, "1")
                || Objects.equals(text, "yes")
                || Objects.equals(text, "on")
                || Objects.equals(text, "enabled");
    }

    private Map<String, Object> runtimeSnapshot(int limit) {
        Map<String, Object> runtime = orderedMap();
        runtime.put("counts", counts());
        runtime.put("systemConfig", queryRows("""
                select config_key, config_value, value_type, enabled, remark, updated_at
                  from wallet_system_config
                 order by config_key
                """));
        runtime.put("chainProfiles", queryRows("""
                select id, chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol, chain_id,
                       gas_policy, enabled, scan_enabled, withdraw_enabled, collection_enabled, transfer_enabled,
                       deposit_confirmations, withdraw_confirmations, default_fee_rate, dust_threshold,
                       scan_batch_size, scan_start_height, scan_max_blocks_per_run, explorer_url, updated_at
                  from chain_profile
                 order by chain, network
                """));
        runtime.put("rpcNodes", queryRows("""
                select id, chain, network, environment, node_label, purpose, connection_type, rpc_url, auth_type,
                       auth_header_name, api_key_ref, username_ref, password_ref, priority, enabled, renewal_due_at,
                       remark, updated_at
                  from chain_rpc_node
                 order by chain, network, environment, purpose, priority, id
                """));
        runtime.put("tokens", queryRows("""
                select id, chain, network, symbol, standard, token_standard, contract_address, contract_address_base58,
                       contract_address_hex, decimals, enabled, min_deposit, min_withdraw, min_deposit_amount,
                       min_withdraw_amount, collect_enabled, collect_threshold, gas_strategy, confirmation_required,
                       updated_at
                  from token_config
                 order by chain, symbol, network nulls first
                """));
        runtime.put("assets", queryRows("""
                select id, chain, symbol, asset_kind, contract_address, decimals, native_asset, active,
                       min_transfer, min_withdraw, updated_at
                  from chain_asset
                 order by chain, native_asset desc, symbol
                """));
        runtime.put("walletPublicKeys", queryRows("""
                select key_slot, key_role, key_type, network, public_key, enabled, remark, updated_at
                  from wallet_public_key
                 order by key_slot
                """));
        runtime.put("scanHeights", queryRows("""
                select id, chain, scanner_name, best_height, safe_height, status, updated_at
                  from chain_scan_height
                 order by chain, scanner_name
                """));
        runtime.put("balances", queryRows("""
                select id, chain, asset_symbol, account_id, available_balance, locked_balance, total_balance, updated_at
                  from ledger_balance
                 order by updated_at desc, id desc
                 limit ?
                """, limit));
        runtime.put("addresses", queryRows("""
                select id, chain, asset_symbol, account_id, user_id, biz, address_index, address, owner_address,
                       derivation_path, wallet_role, enabled, created_at, updated_at
                  from chain_address
                 order by id desc
                 limit ?
                """, limit));
        runtime.put("transactions", recentTransactions(limit));
        return runtime;
    }

    private List<Map<String, Object>> recentTransactions(int limit) {
        return queryRows("""
                select * from (
                  select 'DEPOSIT' as event_type, id, chain, asset_symbol, tx_hash, from_address, to_address,
                         amount, null::numeric as fee, status, confirmations, block_height, credited, created_at, updated_at
                    from deposit_record
                  union all
                  select 'WITHDRAW' as event_type, id, chain, asset_symbol, tx_hash, from_address, to_address,
                         amount, fee, status, null::integer as confirmations, null::bigint as block_height,
                         null::boolean as credited, created_at, updated_at
                    from withdrawal_order
                  union all
                  select 'COLLECTION' as event_type, id, chain, asset_symbol, tx_hash, from_address, to_address,
                         amount, fee, status, null::integer as confirmations, null::bigint as block_height,
                         null::boolean as credited, created_at, updated_at
                    from collection_record
                ) t
                order by updated_at desc
                limit ?
                """, limit);
    }

    private Map<String, Object> adminPayload(int limit) {
        Map<String, Object> payload = orderedMap();
        payload.put("generatedAt", Instant.now().toString());
        payload.put("tables", adminTableMetadata());
        payload.put("secretStatus", secretStatus());
        Map<String, Object> rows = orderedMap();
        for (Map.Entry<String, TableSpec> entry : ADMIN_TABLES.entrySet()) {
            rows.put(entry.getKey(), tableRows(entry.getKey(), entry.getValue(), limit));
        }
        payload.put("rows", rows);
        return payload;
    }

    private List<Map<String, Object>> secretStatus() {
        return List.of(row(
                "name", "SW_ED25519_SEED",
                "scope", "SOLANA/TON/APTOS/SUI Ed25519 master seed",
                "configured", ed25519MasterSeed != null && !ed25519MasterSeed.isBlank(),
                "storage", "environment/property only",
                "editable", false,
                "remark", "Not stored in wallet_public_key or displayed in admin UI; used to derive Ed25519 default hot wallets and signing keys."
        ));
    }

    private Map<String, Object> adminTableMetadata() {
        Map<String, Object> metadata = orderedMap();
        for (Map.Entry<String, TableSpec> entry : ADMIN_TABLES.entrySet()) {
            metadata.put(entry.getKey(), tableMetadata(entry.getKey(), entry.getValue()));
        }
        return metadata;
    }

    private Map<String, Object> tableMetadata(String table, TableSpec spec) {
        Map<String, Object> metadata = orderedMap();
        metadata.put("table", table);
        metadata.put("idColumn", spec.idColumn());
        metadata.put("editableColumns", new ArrayList<>(spec.editableColumns()));
        metadata.put("description", spec.description());
        metadata.put("warning", spec.warning());
        metadata.put("columns", columns(table));
        return metadata;
    }

    private List<Map<String, Object>> tableRows(String table, TableSpec spec, int limit) {
        return queryRows("select * from " + quote(table) + " order by " + spec.orderBy() + " limit ?", limit);
    }

    private List<Map<String, Object>> tableRowsById(String table, TableSpec spec, String id, Map<String, String> columnTypes) {
        return queryRows(
                "select * from " + quote(table) + " where " + quote(spec.idColumn()) + " = ?",
                coerce(id, columnTypes.get(spec.idColumn()))
        );
    }

    private Map<String, Object> counts() {
        Map<String, Object> counts = orderedMap();
        counts.put("enabledChains", count("select count(*) from chain_profile where enabled = true"));
        counts.put("enabledTokens", count("select count(*) from token_config where enabled = true"));
        counts.put("enabledRpcNodes", count("select count(*) from chain_rpc_node where enabled = true"));
        counts.put("addresses", count("select count(*) from chain_address"));
        counts.put("balances", count("select count(*) from ledger_balance"));
        counts.put("deposits", count("select count(*) from deposit_record"));
        counts.put("withdrawals", count("select count(*) from withdrawal_order"));
        counts.put("collections", count("select count(*) from collection_record"));
        counts.put("disabledGlobalSwitches", count("""
                select count(*) from wallet_system_config
                 where enabled = false or lower(config_value) in ('false','0','off','disabled')
                """));
        return counts;
    }

    private Map<String, Object> projectInfo() {
        Map<String, Object> project = orderedMap();
        project.put("name", "surprising-wallet");
        project.put("title", "TokDou 多链钱包后端");
        project.put("summary", "面向交易所托管场景的 Java 多链钱包服务，覆盖地址生成、充值扫描入账、归集、提现、二次签名、广播、确认、账务对账和配置治理。");
        project.put("repository", "https://github.com/lilaizhencn/surprising-wallet");
        project.put("backendPort", 8002);
        project.put("defaultFrontendDevApi", "http://localhost:8002");
        project.put("defaultFrontendBuildApi", "https://api.tokdou.com");
        project.put("supportedChains", List.of(
                "BTC", "LTC", "DOGE", "BCH",
                "ETH", "BNB", "POLYGON", "ARBITRUM", "OPTIMISM", "BASE", "AVAX_C",
                "TRON", "SOLANA", "TON", "APTOS", "SUI"
        ));
        project.put("supportedTokens", "EVM ERC20, TRON TRC20, SOL/SPL, TON Jetton, Aptos Fungible Asset, Sui Coin/Token profiles via token_config");
        project.put("liveTestBoundary", "当前自动化覆盖本地 regtest、Hardhat fork、DB-only 和外部 testnet/devnet 连通性；带真实资金的广播确认必须配置签名私钥、Ed25519 seed、稳定 RPC 和测试币余额。");
        return project;
    }

    private Map<String, Object> apiInfo() {
        Map<String, Object> api = orderedMap();
        api.put("dashboard", "GET /wallet/v1/dashboard");
        api.put("address", "POST /wallet/v1/address?chain={chain}&userId={userId}&biz={biz}");
        api.put("balances", "GET /wallet/v1/balance/all");
        api.put("addressTransactions", "GET /wallet/v1/dashboard/address-transactions?address={address}&chain={chain}");
        api.put("adminLogin", "POST /wallet/v1/admin/login with Basic Authorization");
        api.put("adminConfig", "GET/PATCH /wallet/v1/admin/config");
        return api;
    }

    private Map<String, Object> documentation() {
        Map<String, Object> docs = orderedMap();
        docs.put("startup", List.of(
                "JDK 21, Maven 3.8+, PostgreSQL 14+, Redis 6+, Docker, Node.js 18+ are required for the full local matrix.",
                "Initialize PostgreSQL with docs/db/surprising-wallet-init-pgsql.sql before starting wallet-server.",
                "Before wallet-server startup, every enabled chain must have exactly one default hot wallet row in chain_address: native asset, user_id=0, biz=0, address_index=0, wallet_role=DEPOSIT.",
                "Changing wallet_public_key through the admin API is validated before save: candidate BIP32 roots must still derive the default hot wallet rows already stored in chain_address.",
                "Start wallet-sig1, wallet-sig2 and wallet-server from the repository root. wallet-server defaults to port 8002.",
                "Configure SW_DB_PASSWORD, SW_SIG1_MASTER_KEY, SW_SIG2_MASTER_KEY and SW_ED25519_SEED before funded tests or real operation."
        ));
        docs.put("startupRequiredConfig", List.of(
                row("name", "SW_DB_PASSWORD", "usage", "PostgreSQL password for wallet-server"),
                row("name", "SW_SIG1_MASTER_KEY", "usage", "BIP32 tprv used by wallet-sig1"),
                row("name", "SW_SIG2_MASTER_KEY", "usage", "BIP32 tprv used by wallet-sig2"),
                row("name", "SW_ED25519_SEED", "usage", "32-byte Ed25519 seed for SOL/TON/APTOS/SUI; secret env/property, not stored in wallet_public_key"),
                row("name", "sw.wallet.admin.username/password", "usage", "Basic-auth credentials for the admin configuration page"),
                row("name", "wallet_public_key", "usage", "Three enabled BIP32 xpub/public-key slots for BTC-like/EVM/TRON address derivation"),
                row("name", "chain_address default hot wallet", "usage", "One native row per enabled chain at user_id=0, biz=0, address_index=0, wallet_role=DEPOSIT")
        ));
        docs.put("architectureDiagram", """
                graph TD
                  TokDou[TokDou Wallet Page] --> WalletServer[wallet-server REST + jobs]
                  WalletServer --> DB[(PostgreSQL)]
                  WalletServer --> Redis[(Redis)]
                  WalletServer --> Sig1[wallet-sig1]
                  WalletServer --> Sig2[wallet-sig2]
                  WalletServer --> SDK[chain SDK adapters]
                  SDK --> Nodes[RPC / Fullnode / Indexer]
                  DB --> Config[chain_profile / chain_rpc_node / token_config]
                  DB --> Ledger[ledger_balance / deposit_record / withdrawal_order / collection_record]
                """);
        docs.put("chainFlows", chainFlows());
        docs.put("databaseTables", databaseTables());
        docs.put("addChain", List.of(
                "Add chain_profile with one enabled network. runtime_currency_id is internal runtime metadata, not a public API parameter.",
                "Add chain_asset for the native asset, then token_config rows for supported tokens.",
                "Add chain_rpc_node rows for the current environment and each required purpose.",
                "Implement or enable the chain adapter/scanner/withdraw/collection service in wallet-service.",
                "Add regtest/fork/devnet tests and document confirmations, gas policy and scan policy."
        ));
        docs.put("addToken", List.of(
                "Add token_config with chain, network, symbol, contract address, decimals and thresholds.",
                "Add chain_asset token rows when the token participates in the normalized ledger.",
                "Enable collect_enabled only after gas top-up and collection flows are tested.",
                "Run DB-only or fork tests before exposing the token in production."
        ));
        docs.put("javaImplementation", List.of(
                "WalletController exposes address generation and balance query APIs.",
                "WalletDashboardController exposes dashboard/admin APIs for TokDou.",
                "ChainJdbcRepository centralizes DB asset/config/ledger operations.",
                "HotWalletAddressService derives and validates each chain's default hot wallet at startup.",
                "AssetRoutingService maps chain/native/token metadata into internal runtime assets.",
                "WalletContext selects the chain wallet implementation.",
                "Signing transactions are created by wallet-server and signed by wallet-sig1/wallet-sig2."
        ));
        docs.put("regtestAndForkPrinciple", List.of(
                "UTXO regtest starts local BTC/LTC/DOGE/BCH nodes, mines blocks, creates deposits, selects UTXOs, signs, broadcasts and confirms transactions locally.",
                "EVM fork starts Hardhat on 127.0.0.1:8545 from a testnet RPC, deploys mock ERC20 contracts, then runs Java scanner/withdraw/collection tests against the fork.",
                "DB-only tests validate scanner, ledger and idempotency logic without depending on external nodes.",
                "External live tests verify devnet/testnet RPC connectivity; funded spending tests require real test funds and real signing secrets."
        ));
        return docs;
    }

    private List<Map<String, Object>> chainFlows() {
        return List.of(
                row("family", "Bitcoin-like UTXO", "chains", "BTC/LTC/DOGE/BCH", "flow",
                        "create address -> scan blocks/UTXO -> credit ledger -> pick spendable UTXOs -> build unsigned tx -> sig1 -> sig2 -> broadcast -> confirm -> reconcile"),
                row("family", "EVM", "chains", "ETH/BNB/POLYGON/ARBITRUM/OPTIMISM/BASE/AVAX_C", "flow",
                        "create address -> scan native/ERC20 logs -> credit ledger -> reserve nonce/gas -> build transaction -> sig1/sig2 policy -> broadcast -> receipt confirmations -> reconcile"),
                row("family", "TRON", "chains", "TRON", "flow",
                        "create address -> scan TRX/TRC20 -> credit ledger -> estimate bandwidth/energy -> sign -> broadcast -> confirm -> reconcile"),
                row("family", "Ed25519 account chains", "chains", "SOLANA/TON/APTOS/SUI", "flow",
                        "derive account -> scan native/token transfers -> credit ledger -> build chain-specific transaction -> sign with Ed25519 seed -> broadcast -> confirm -> reconcile")
        );
    }

    private List<Map<String, Object>> databaseTables() {
        return List.of(
                row("table", "wallet_system_config", "purpose", "Global scan/withdraw/collection/transfer switches."),
                row("table", "chain_profile", "purpose", "Chain network, runtime currency, confirmations, scan policy and feature switches."),
                row("table", "chain_rpc_node", "purpose", "RPC/fullnode/indexer/faucet endpoints by chain/network/environment/purpose."),
                row("table", "chain_asset", "purpose", "Native and normalized asset definitions."),
                row("table", "token_config", "purpose", "Token contracts, decimals, thresholds and collection policy."),
                row("table", "wallet_public_key", "purpose", "Public keys used by wallet-server to validate multisig/address derivation."),
                row("table", "chain_address", "purpose", "User addresses and the fixed default hot wallet row per chain."),
                row("table", "ledger_balance", "purpose", "Available, locked and total balances per account/chain/asset."),
                row("table", "deposit_record", "purpose", "Normalized deposit events and ledger credit state."),
                row("table", "withdrawal_order", "purpose", "Withdrawal state machine, target address, tx hash and fee."),
                row("table", "collection_record", "purpose", "Address-to-hot-wallet collection state."),
                row("table", "utxo_record", "purpose", "Bitcoin-like UTXO state, locks and spend references."),
                row("table", "chain_signing_transaction", "purpose", "Signing workflow records for business transactions.")
        );
    }

    private Map<String, Object> operationsInfo() {
        Map<String, Object> operations = orderedMap();
        operations.put("adminAuth", "Use HTTP Basic auth. Configure sw.wallet.admin.username and sw.wallet.admin.password from environment variables in production.");
        operations.put("importantWarnings", List.of(
                "Disabling wallet_system_config switches stops a whole class of jobs.",
                "Changing chain_profile network/chain_id/rpc policy can break scanning and transaction replay safety.",
                "Changing wallet_public_key affects address derivation; the admin API rejects changes that no longer match existing default hot wallet rows.",
                "Do not store raw RPC API keys in Git; use api_key_ref/password_ref or environment-backed secret injection."
        ));
        operations.put("testedOn2026_06_25", List.of(
                "DB-only account-chain tests passed.",
                "BTC/LTC/DOGE/BCH local regtest passed deposit, withdrawal, collection, UTXO locking and bulk broadcast flows.",
                "ETH/ARBITRUM/OPTIMISM/BASE/AVAX_C Hardhat fork tests passed with stable public endpoints.",
                "BNB and POLYGON public fork RPCs were not promoted as stable defaults; use private/archive-capable RPCs for required fork coverage.",
                "Funded live spending was blocked by missing signing secrets and funded test accounts; connectivity tests passed with JDK 21."
        ));
        return operations;
    }

    private List<Map<String, Object>> queryRows(String sql, Object... args) {
        try {
            return jdbcTemplate.queryForList(sql, args).stream()
                    .map(this::sanitizeRow)
                    .toList();
        } catch (DataAccessException e) {
            log.warn("dashboard query skipped: {}", oneLine(sql), e);
            return List.of();
        }
    }

    private long count(String sql) {
        try {
            Long value = jdbcTemplate.queryForObject(sql, Long.class);
            return value == null ? 0L : value;
        } catch (DataAccessException e) {
            log.warn("dashboard count skipped: {}", oneLine(sql), e);
            return 0L;
        }
    }

    private List<Map<String, Object>> columns(String table) {
        return queryRows("""
                select column_name, data_type, is_nullable
                  from information_schema.columns
                 where table_schema = 'public' and table_name = ?
                 order by ordinal_position
                """, table);
    }

    private Map<String, String> columnTypes(String table) {
        Map<String, String> types = new LinkedHashMap<>();
        for (Map<String, Object> column : columns(table)) {
            Object name = column.get("column_name");
            Object type = column.get("data_type");
            if (name != null && type != null) {
                types.put(String.valueOf(name), String.valueOf(type));
            }
        }
        return types;
    }

    private Map<String, Object> sanitizeRow(Map<String, Object> source) {
        Map<String, Object> row = orderedMap();
        source.forEach((key, value) -> row.put(key, sanitizeValue(key, value)));
        return row;
    }

    private Object sanitizeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        String lowerKey = key.toLowerCase(Locale.ROOT);
        if (Objects.equals(lowerKey, "api_key") || Objects.equals(lowerKey, "password")
                || lowerKey.contains("private") || lowerKey.contains("secret")) {
            String text = String.valueOf(value);
            return text.isBlank() ? "" : "***configured***";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant().toString();
        }
        return value;
    }

    private void requireAdmin(String authorization) {
        if (adminUsername == null || adminUsername.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wallet admin credentials are not configured");
        }
        if (authorization == null || !authorization.startsWith("Basic ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wallet admin authentication required");
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring("Basic ".length()).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid wallet admin authorization");
        }
        int split = decoded.indexOf(':');
        if (split <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid wallet admin authorization");
        }
        String user = decoded.substring(0, split);
        String password = decoded.substring(split + 1);
        if (!constantEquals(user, adminUsername) || !constantEquals(password, adminPassword)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid wallet admin credentials");
        }
    }

    private boolean constantEquals(String actual, String expected) {
        byte[] actualBytes = actual == null ? new byte[0] : actual.getBytes(StandardCharsets.UTF_8);
        byte[] expectedBytes = expected == null ? new byte[0] : expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(actualBytes, expectedBytes);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractUpdates(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return Map.of();
        }
        Object updates = body.get("updates");
        if (updates instanceof Map<?, ?> map) {
            Map<String, Object> normalized = orderedMap();
            map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
            return normalized;
        }
        return body;
    }

    private Object coerce(Object value, String dataType) {
        if (value == null || dataType == null) {
            return value;
        }
        if (value instanceof String text && text.isBlank()) {
            return null;
        }
        String type = dataType.toLowerCase(Locale.ROOT);
        if (type.contains("boolean")) {
            if (value instanceof Boolean bool) {
                return bool;
            }
            return Boolean.parseBoolean(String.valueOf(value));
        }
        if (type.contains("integer")) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            return Integer.parseInt(String.valueOf(value));
        }
        if (type.contains("bigint")) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            return Long.parseLong(String.valueOf(value));
        }
        if (type.contains("numeric")) {
            if (value instanceof BigDecimal decimal) {
                return decimal;
            }
            return new BigDecimal(String.valueOf(value));
        }
        return value;
    }

    private TableSpec requireTable(String table) {
        TableSpec spec = ADMIN_TABLES.get(table);
        if (spec == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported config table: " + table);
        }
        return spec;
    }

    private static Map<String, TableSpec> adminTables() {
        Map<String, TableSpec> tables = new LinkedHashMap<>();
        tables.put("wallet_system_config", new TableSpec("config_key", set("config_value", "value_type", "enabled", "remark"),
                "config_key",
                "Global switches for scan, withdraw and collection jobs; transfer is reserved for future internal transfer entry points.",
                "Disabling these switches affects every chain."));
        tables.put("chain_profile", new TableSpec("id", set("enabled", "scan_enabled", "withdraw_enabled", "collection_enabled",
                "transfer_enabled", "rpc_url", "explorer_url", "deposit_confirmations", "withdraw_confirmations",
                "default_fee_rate", "dust_threshold", "chain_id", "gas_policy", "scan_batch_size", "scan_start_height",
                "scan_max_blocks_per_run"),
                "chain, network, id",
                "Per-chain network, confirmation, scan and feature configuration.",
                "Changing network, chain_id or scan height can cause missed deposits or duplicate scans."));
        tables.put("chain_rpc_node", new TableSpec("id", set("rpc_url", "enabled", "priority", "remark", "purpose",
                "environment", "network", "node_label", "connection_type", "auth_type", "auth_header_name",
                "api_key_ref", "username_ref", "password_ref"),
                "chain, network, environment, priority, id",
                "RPC/fullnode/indexer/faucet endpoints used by scanners and broadcasters.",
                "Disable unstable public RPCs instead of leaving them as high-priority nodes."));
        tables.put("chain_asset", new TableSpec("id", set("asset_kind", "contract_address", "decimals", "native_asset",
                "active", "min_transfer", "min_withdraw"),
                "chain, native_asset desc, symbol",
                "Normalized native/token asset definitions used by the ledger.",
                "Changing decimals or symbols after balances exist requires reconciliation."));
        tables.put("token_config", new TableSpec("id", set("standard", "contract_address", "decimals", "enabled",
                "min_deposit", "min_withdraw", "collect_enabled", "network", "token_standard",
                "contract_address_base58", "contract_address_hex", "min_deposit_amount", "min_withdraw_amount",
                "collect_threshold", "gas_strategy", "confirmation_required"),
                "chain, symbol, network nulls first, id",
                "Token contracts, thresholds, collection policy and gas strategy.",
                "Only enable collection after gas top-up and withdrawal tests pass for this token."));
        tables.put("wallet_public_key", new TableSpec("key_slot", set("public_key", "enabled", "remark"),
                "key_slot",
                "Public keys used by wallet-server for derivation and verification.",
                "These keys must match signer private roots; mistakes can generate unusable addresses."));
        return Collections.unmodifiableMap(tables);
    }

    private static Set<String> set(String... values) {
        Set<String> set = new LinkedHashSet<>();
        Collections.addAll(set, values);
        return Collections.unmodifiableSet(set);
    }

    private static String normalizeIdentifier(String value) {
        if (value == null || !value.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid identifier: " + value);
        }
        return value;
    }

    private static String quote(String identifier) {
        return "\"" + normalizeIdentifier(identifier) + "\"";
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, 500);
    }

    private static String oneLine(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> row(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("row requires key/value pairs");
        }
        Map<String, Object> row = orderedMap();
        for (int i = 0; i < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    private record TableSpec(
            String idColumn,
            Set<String> editableColumns,
            String orderBy,
            String description,
            String warning
    ) {
    }
}
