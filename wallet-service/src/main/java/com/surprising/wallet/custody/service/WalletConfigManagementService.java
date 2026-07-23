package com.surprising.wallet.custody.service;

import com.surprising.wallet.config.WalletEnvironmentPolicy;
import com.surprising.wallet.config.WalletRpcPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.repository.CustodyRepository;

@Service
public class WalletConfigManagementService {
    private final JdbcTemplate jdbc;
    private final CustodyRepository custodyRepository;
    private final String environment;
    private final HttpClient httpClient;

    @Autowired
    public WalletConfigManagementService(JdbcTemplate jdbc,
                                         CustodyRepository custodyRepository,
                                         @Value("${sw.app.env.name:dev}") String environment) {
        this(jdbc, custodyRepository, environment,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    WalletConfigManagementService(JdbcTemplate jdbc, CustodyRepository custodyRepository,
                                  String environment, HttpClient httpClient) {
        this.jdbc = jdbc;
        this.custodyRepository = custodyRepository;
        this.environment = normalizeLower(environment);
        this.httpClient = httpClient;
    }

    public List<ChainView> listChains(CustodyPrincipal actor, String search, Boolean enabled,
                                      String network, String family, String token,
                                      Boolean scanEnabled, Boolean withdrawEnabled,
                                      Boolean collectionEnabled, Boolean transferEnabled) {
        requirePlatformAdmin(actor);
        String query = normalize(search);
        String networkFilter = normalize(network);
        String familyFilter = normalize(family);
        String tokenFilter = normalize(token);
        return jdbc.queryForList(CHAIN_SELECT + " order by p.chain, p.network").stream()
                .map(this::chainView)
                .filter(row -> query.isEmpty()
                        || normalize(row.chain()).contains(query)
                        || normalize(row.network()).contains(query)
                        || normalize(row.family()).contains(query)
                        || normalize(row.nativeSymbol()).contains(query))
                .filter(row -> enabled == null || row.enabled() == enabled)
                .filter(row -> networkFilter.isEmpty() || normalize(row.network()).equals(networkFilter))
                .filter(row -> familyFilter.isEmpty() || normalize(row.family()).equals(familyFilter))
                .filter(row -> tokenFilter.isEmpty() || row.tokenSymbols().stream()
                        .map(WalletConfigManagementService::normalize).anyMatch(value -> value.contains(tokenFilter)))
                .filter(row -> scanEnabled == null || row.scanEnabled() == scanEnabled)
                .filter(row -> withdrawEnabled == null || row.withdrawEnabled() == withdrawEnabled)
                .filter(row -> collectionEnabled == null || row.collectionEnabled() == collectionEnabled)
                .filter(row -> transferEnabled == null || row.transferEnabled() == transferEnabled)
                .toList();
    }

    public ChainDetailView getChain(CustodyPrincipal actor, long id) {
        requirePlatformAdmin(actor);
        ChainView chain = requireChain(id);
        List<RpcNodeView> rpcNodes = listRpcNodesInternal(id);
        List<TokenView> tokens = listTokensInternal("", chain.chain(), null).stream()
                .filter(token -> token.network().equalsIgnoreCase(chain.network()))
                .toList();
        List<String> checks = chainChecks(chain, rpcNodes, tokens);
        return new ChainDetailView(chain, rpcNodes, tokens, checks,
                WalletEnvironmentPolicy.isProduction(environment), environment);
    }

    @Transactional
    public ChainDetailView createChain(CustodyPrincipal actor, ChainCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        ChainValues values = mergeChain(null, command);
        validateChainValues(values);
        if (values.enabled()) {
            validateCanEnable(values.chain(), values.network(), null);
        }
        Long id = jdbc.queryForObject("""
                insert into chain_profile(
                    chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                    explorer_url, deposit_confirmations, withdraw_confirmations, default_fee_rate,
                    dust_threshold, enabled, chain_id, gas_policy, scan_batch_size,
                    scan_enabled, withdraw_enabled, collection_enabled, transfer_enabled,
                    scan_start_height, scan_max_blocks_per_run, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                returning id
                """, Long.class,
                values.chain(), values.network(), values.family(), values.runtimeCurrencyId(),
                values.bip44CoinType(), values.nativeSymbol(), values.explorerUrl(),
                values.depositConfirmations(), values.withdrawConfirmations(), values.defaultFeeRate(),
                values.dustThreshold(), values.enabled(), values.chainId(), values.gasPolicy(),
                values.scanBatchSize(), values.scanEnabled(), values.withdrawEnabled(),
                values.collectionEnabled(), values.transferEnabled(), values.scanStartHeight(),
                values.scanMaxBlocksPerRun());
        audit(actor, "WALLET_CHAIN.CREATE", "CHAIN_PROFILE", String.valueOf(id), sourceIp,
                "{\"chain\":" + json(values.chain()) + ",\"network\":" + json(values.network()) + "}");
        return getChain(actor, id);
    }

    @Transactional
    public ChainDetailView updateChain(CustodyPrincipal actor, long id,
                                       ChainCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        ChainView current = requireChain(id);
        ChainValues values = mergeChain(current, command);
        validateChainValues(values);
        if ((!current.chain().equalsIgnoreCase(values.chain())
                || !current.network().equalsIgnoreCase(values.network())
                || current.runtimeCurrencyId() != values.runtimeCurrencyId()
                || current.bip44CoinType() != values.bip44CoinType()) && chainHasRuntimeData(current.chain())) {
            throw new IllegalStateException("chain identity, network, runtime currency and BIP44 coin type are locked after addresses or transactions exist");
        }
        if (values.enabled()) {
            validateCanEnable(values.chain(), values.network(), id);
        }
        jdbc.update("""
                update chain_profile
                   set chain = ?, network = ?, family = ?, runtime_currency_id = ?,
                       bip44_coin_type = ?, native_symbol = ?, explorer_url = ?,
                       deposit_confirmations = ?, withdraw_confirmations = ?, default_fee_rate = ?,
                       dust_threshold = ?, enabled = ?, chain_id = ?, gas_policy = ?,
                       scan_batch_size = ?, scan_enabled = ?, withdraw_enabled = ?,
                       collection_enabled = ?, transfer_enabled = ?, scan_start_height = ?,
                       scan_max_blocks_per_run = ?, updated_at = now()
                 where id = ?
                """, values.chain(), values.network(), values.family(), values.runtimeCurrencyId(),
                values.bip44CoinType(), values.nativeSymbol(), values.explorerUrl(),
                values.depositConfirmations(), values.withdrawConfirmations(), values.defaultFeeRate(),
                values.dustThreshold(), values.enabled(), values.chainId(), values.gasPolicy(),
                values.scanBatchSize(), values.scanEnabled(), values.withdrawEnabled(),
                values.collectionEnabled(), values.transferEnabled(), values.scanStartHeight(),
                values.scanMaxBlocksPerRun(), id);
        if (!current.chain().equalsIgnoreCase(values.chain())
                || !current.network().equalsIgnoreCase(values.network())) {
            jdbc.update("update chain_rpc_node set chain = ?, network = ?, updated_at = now() where chain = ? and network = ?",
                    values.chain(), values.network(), current.chain(), current.network());
            jdbc.update("update token_config set chain = ?, network = ?, updated_at = now() where chain = ? and network = ?",
                    values.chain(), values.network(), current.chain(), current.network());
            if (!current.chain().equalsIgnoreCase(values.chain())) {
                jdbc.update("update chain_asset set chain = ?, updated_at = now() where chain = ?",
                        values.chain(), current.chain());
            }
        }
        audit(actor, "WALLET_CHAIN.UPDATE", "CHAIN_PROFILE", String.valueOf(id), sourceIp,
                "{\"chain\":" + json(values.chain()) + ",\"network\":" + json(values.network()) + "}");
        return getChain(actor, id);
    }

    @Transactional
    public ChainDetailView updateChainSwitches(CustodyPrincipal actor, long id,
                                               ChainSwitchCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        ChainView current = requireChain(id);
        if (command == null || command.enabled() == null || command.scanEnabled() == null
                || command.withdrawEnabled() == null || command.collectionEnabled() == null
                || command.transferEnabled() == null) {
            throw new IllegalArgumentException("all chain switches are required");
        }
        if (command.enabled()) {
            validateCanEnable(current.chain(), current.network(), id);
        }
        jdbc.update("""
                update chain_profile
                   set enabled = ?, scan_enabled = ?, withdraw_enabled = ?,
                       collection_enabled = ?, transfer_enabled = ?, updated_at = now()
                 where id = ?
                """, command.enabled(), command.scanEnabled(), command.withdrawEnabled(),
                command.collectionEnabled(), command.transferEnabled(), id);
        audit(actor, "WALLET_CHAIN.SWITCHES_UPDATE", "CHAIN_PROFILE", String.valueOf(id), sourceIp,
                "{\"enabled\":" + command.enabled() + "}");
        return getChain(actor, id);
    }

    public List<RpcNodeView> listRpcNodes(CustodyPrincipal actor, long chainId) {
        requirePlatformAdmin(actor);
        requireChain(chainId);
        return listRpcNodesInternal(chainId);
    }

    @Transactional
    public RpcNodeView createRpcNode(CustodyPrincipal actor, long chainId,
                                     RpcNodeCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        ChainView chain = requireChain(chainId);
        RpcValues values = mergeRpc(null, command, chain);
        validateRpc(values);
        Long id = jdbc.queryForObject("""
                insert into chain_rpc_node(
                    chain, network, environment, node_label, purpose, connection_type, rpc_url,
                    auth_type, auth_header_name, api_key, username, password, priority,
                    min_request_interval_ms, enabled, renewal_due_at, remark, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) returning id
                """, Long.class, chain.chain(), chain.network(), values.environment(), values.nodeLabel(),
                values.purpose(), values.connectionType(), values.rpcUrl(), values.authType(),
                values.authHeaderName(), values.apiKey(), values.username(), values.password(),
                values.priority(), values.minRequestIntervalMs(), values.enabled(),
                values.renewalDueAt(), values.remark());
        audit(actor, "WALLET_RPC.CREATE", "CHAIN_RPC_NODE", String.valueOf(id), sourceIp,
                rpcAudit(values));
        return requireRpcNode(chainId, id);
    }

    @Transactional
    public RpcNodeView updateRpcNode(CustodyPrincipal actor, long chainId, long nodeId,
                                     RpcNodeCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        ChainView chain = requireChain(chainId);
        RpcStored current = requireRpcStored(chain, nodeId);
        RpcValues values = mergeRpc(current, command, chain);
        validateRpc(values);
        if (current.enabled() && (!values.enabled()
                || !current.purpose().equalsIgnoreCase(values.purpose())
                || !current.environment().equalsIgnoreCase(values.environment()))) {
            validateCanDisableRpc(chain, nodeId, current.purpose(), current.environment());
        }
        jdbc.update("""
                update chain_rpc_node
                   set environment = ?, node_label = ?, purpose = ?, connection_type = ?,
                       rpc_url = ?, auth_type = ?, auth_header_name = ?, api_key = ?, username = ?,
                       password = ?, priority = ?, min_request_interval_ms = ?, enabled = ?,
                       renewal_due_at = ?, remark = ?, updated_at = now()
                 where id = ? and chain = ? and network = ?
                """, values.environment(), values.nodeLabel(), values.purpose(), values.connectionType(),
                values.rpcUrl(), values.authType(), values.authHeaderName(), values.apiKey(),
                values.username(), values.password(), values.priority(), values.minRequestIntervalMs(),
                values.enabled(), values.renewalDueAt(), values.remark(), nodeId,
                chain.chain(), chain.network());
        audit(actor, "WALLET_RPC.UPDATE", "CHAIN_RPC_NODE", String.valueOf(nodeId), sourceIp,
                rpcAudit(values));
        return requireRpcNode(chainId, nodeId);
    }

    @Transactional
    public void deleteRpcNode(CustodyPrincipal actor, long chainId, long nodeId, String sourceIp) {
        requirePlatformAdmin(actor);
        ChainView chain = requireChain(chainId);
        RpcStored node = requireRpcStored(chain, nodeId);
        if (node.enabled()) {
            throw new IllegalStateException("disable the RPC node before deleting it");
        }
        jdbc.update("delete from chain_rpc_node where id = ? and chain = ? and network = ?",
                nodeId, chain.chain(), chain.network());
        audit(actor, "WALLET_RPC.DELETE", "CHAIN_RPC_NODE", String.valueOf(nodeId), sourceIp,
                "{\"chain\":" + json(chain.chain()) + ",\"network\":" + json(chain.network()) + "}");
    }

    public RpcTestView testRpcNode(CustodyPrincipal actor, long chainId, long nodeId) {
        requirePlatformAdmin(actor);
        ChainView chain = requireChain(chainId);
        RpcStored node = requireRpcStored(chain, nodeId);
        long started = System.nanoTime();
        try {
            URI uri = URI.create(node.rpcUrl());
            if ("ws".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme())) {
                WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(8));
                applyAuth(builder, node);
                WebSocket socket = builder.buildAsync(uri, new WebSocket.Listener() { })
                        .get(8, TimeUnit.SECONDS);
                socket.abort();
                return saveRpcTest(nodeId,
                        new RpcTestView(true, null, elapsedMillis(started), null, Instant.now()));
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8));
            applyAuth(builder, node);
            if (node.connectionType().toUpperCase(Locale.ROOT).contains("JSON_RPC")) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"web3_clientVersion\",\"params\":[]}"));
            } else {
                builder.GET();
            }
            HttpResponse<Void> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            boolean success = response.statusCode() >= 200 && response.statusCode() < 400;
            return saveRpcTest(nodeId, new RpcTestView(success, response.statusCode(), latency,
                    success ? null : "RPC returned HTTP " + response.statusCode(), Instant.now()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return saveRpcTest(nodeId, new RpcTestView(
                    false, null, elapsedMillis(started), "RPC test interrupted", Instant.now()));
        } catch (Exception e) {
            return saveRpcTest(nodeId, new RpcTestView(
                    false, null, elapsedMillis(started), safeError(e), Instant.now()));
        }
    }

    public List<TokenView> listTokens(CustodyPrincipal actor, String search, String chain,
                                      String network, String standard, Boolean enabled,
                                      Boolean effectiveEnabled) {
        requirePlatformAdmin(actor);
        String networkFilter = normalize(network);
        String standardFilter = normalize(standard);
        return listTokensInternal(search, chain, enabled).stream()
                .filter(row -> networkFilter.isEmpty() || normalize(row.network()).equals(networkFilter))
                .filter(row -> standardFilter.isEmpty() || normalize(row.standard()).equals(standardFilter))
                .filter(row -> effectiveEnabled == null || row.effectiveEnabled() == effectiveEnabled)
                .toList();
    }

    @Transactional
    public TokenView createToken(CustodyPrincipal actor, TokenCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        TokenValues values = mergeToken(null, command);
        validateToken(values);
        Long id = jdbc.queryForObject("""
                insert into token_config(
                    chain, network, symbol, standard, token_standard, contract_address,
                    contract_address_base58, contract_address_hex, decimals, enabled,
                    min_deposit, min_withdraw, min_deposit_amount, min_withdraw_amount,
                    collect_enabled, collect_threshold, gas_strategy, confirmation_required, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) returning id
                """, Long.class, values.chain(), values.network(), values.symbol(), values.standard(),
                values.standard(), values.contractAddress(), values.contractAddressBase58(),
                values.contractAddressHex(), values.decimals(), values.enabled(), values.minDeposit(),
                values.minWithdraw(), values.minDeposit(), values.minWithdraw(), values.collectEnabled(),
                values.collectThreshold(), values.gasStrategy(), values.confirmationRequired());
        if (values.enabled()) {
            upsertAsset(values);
        }
        audit(actor, "WALLET_TOKEN.CREATE", "TOKEN_CONFIG", String.valueOf(id), sourceIp,
                tokenAudit(values));
        return requireToken(id);
    }

    @Transactional
    public TokenView updateToken(CustodyPrincipal actor, long id,
                                 TokenCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        TokenView current = requireToken(id);
        TokenValues values = mergeToken(current, command);
        validateToken(values);
        if ((!current.chain().equalsIgnoreCase(values.chain())
                || !current.network().equalsIgnoreCase(values.network())
                || !current.symbol().equalsIgnoreCase(values.symbol())
                || !current.contractAddress().equalsIgnoreCase(values.contractAddress())
                || current.decimals() != values.decimals()) && tokenHasRuntimeData(current.chain(), current.symbol())) {
            throw new IllegalStateException("token identity, network, contract and decimals are locked after wallet data exists");
        }
        jdbc.update("""
                update token_config
                   set chain = ?, network = ?, symbol = ?, standard = ?, token_standard = ?,
                       contract_address = ?, contract_address_base58 = ?, contract_address_hex = ?,
                       decimals = ?, enabled = ?, min_deposit = ?, min_withdraw = ?,
                       min_deposit_amount = ?, min_withdraw_amount = ?, collect_enabled = ?,
                       collect_threshold = ?, gas_strategy = ?, confirmation_required = ?, updated_at = now()
                 where id = ?
                """, values.chain(), values.network(), values.symbol(), values.standard(), values.standard(),
                values.contractAddress(), values.contractAddressBase58(), values.contractAddressHex(),
                values.decimals(), values.enabled(), values.minDeposit(), values.minWithdraw(),
                values.minDeposit(), values.minWithdraw(), values.collectEnabled(), values.collectThreshold(),
                values.gasStrategy(), values.confirmationRequired(), id);
        if (!current.chain().equalsIgnoreCase(values.chain()) || !current.symbol().equalsIgnoreCase(values.symbol())) {
            jdbc.update("delete from chain_asset where chain = ? and symbol = ? and native_asset = false",
                    current.chain(), current.symbol());
        }
        if (values.enabled()) {
            upsertAsset(values);
        } else if (current.enabled()) {
            setAssetActive(current.chain(), current.symbol(), false);
        }
        audit(actor, "WALLET_TOKEN.UPDATE", "TOKEN_CONFIG", String.valueOf(id), sourceIp,
                tokenAudit(values));
        return requireToken(id);
    }

    @Transactional
    public TokenView updateTokenStatus(CustodyPrincipal actor, long id,
                                       TokenStatusCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        if (command == null || command.enabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        TokenView current = requireToken(id);
        if (command.enabled()) {
            validateToken(new TokenValues(current.chain(), current.network(), current.symbol(),
                    current.standard(), current.contractAddress(), current.contractAddressBase58(),
                    current.contractAddressHex(), current.decimals(), true, current.collectEnabled(),
                    current.minDeposit(), current.minWithdraw(), current.collectThreshold(),
                    current.gasStrategy(), current.confirmationRequired()));
        }
        boolean collectEnabled = command.enabled() && current.collectEnabled();
        jdbc.update("update token_config set enabled = ?, collect_enabled = ?, updated_at = now() where id = ?",
                command.enabled(), collectEnabled, id);
        if (command.enabled()) {
            upsertAsset(new TokenValues(current.chain(), current.network(), current.symbol(),
                    current.standard(), current.contractAddress(), current.contractAddressBase58(),
                    current.contractAddressHex(), current.decimals(), true, collectEnabled,
                    current.minDeposit(), current.minWithdraw(), current.collectThreshold(),
                    current.gasStrategy(), current.confirmationRequired()));
        } else {
            setAssetActive(current.chain(), current.symbol(), false);
        }
        audit(actor, "WALLET_TOKEN.STATUS_UPDATE", "TOKEN_CONFIG", String.valueOf(id), sourceIp,
                "{\"enabled\":" + command.enabled() + "}");
        return requireToken(id);
    }

    public List<Map<String, Object>> auditLog(CustodyPrincipal actor, int limit, int offset) {
        requirePlatformAdmin(actor);
        return custodyRepository.listPlatformAudit(limit, offset);
    }

    private List<RpcNodeView> listRpcNodesInternal(long chainId) {
        ChainView chain = requireChain(chainId);
        return jdbc.queryForList("""
                select id, chain, network, environment, node_label, purpose, connection_type,
                       rpc_url, auth_type, auth_header_name, api_key, username, password,
                       priority, min_request_interval_ms, enabled, renewal_due_at, remark,
                       last_checked_at, last_latency_ms, last_http_status, last_error,
                       created_at, updated_at
                  from chain_rpc_node
                 where chain = ? and network = ?
                 order by environment, purpose, priority, id
                """, chain.chain(), chain.network()).stream().map(this::rpcView).toList();
    }

    private List<TokenView> listTokensInternal(String search, String chain, Boolean enabled) {
        String query = normalize(search);
        String chainFilter = normalize(chain);
        RuntimeSwitches runtime = loadRuntimeSwitches();
        return jdbc.queryForList("""
                select t.id, t.chain, t.network, t.symbol,
                       coalesce(nullif(t.token_standard, ''), t.standard) as standard,
                       t.contract_address, t.contract_address_base58, t.contract_address_hex,
                       t.decimals, t.enabled, t.collect_enabled,
                       coalesce(t.min_deposit_amount, t.min_deposit) as min_deposit,
                       coalesce(t.min_withdraw_amount, t.min_withdraw) as min_withdraw,
                       t.collect_threshold, t.gas_strategy, t.confirmation_required,
                       coalesce(a.active, false) as asset_active,
                       coalesce(p.enabled, false) as chain_enabled,
                       coalesce(p.scan_enabled, false) as chain_scan_enabled,
                       coalesce(p.withdraw_enabled, false) as chain_withdraw_enabled,
                       coalesce(p.collection_enabled, false) as chain_collection_enabled,
                       coalesce(p.transfer_enabled, false) as chain_transfer_enabled,
                       t.created_at, t.updated_at
                  from token_config t
                  left join chain_asset a on a.chain = t.chain and a.symbol = t.symbol
                                           and a.native_asset = false
                  left join chain_profile p on p.chain = t.chain and p.network = t.network
                 order by t.symbol, t.chain
                """).stream().map(row -> tokenView(row, runtime))
                .filter(row -> query.isEmpty()
                        || normalize(row.symbol()).contains(query)
                        || normalize(row.contractAddress()).contains(query))
                .filter(row -> chainFilter.isEmpty() || normalize(row.chain()).equals(chainFilter))
                .filter(row -> enabled == null || row.enabled() == enabled)
                .toList();
    }

    private ChainView requireChain(long id) {
        try {
            return chainView(jdbc.queryForMap(CHAIN_SELECT + " where p.id = ?", id));
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("chain profile not found");
        }
    }

    private RpcNodeView requireRpcNode(long chainId, long nodeId) {
        return listRpcNodesInternal(chainId).stream().filter(node -> node.id() == nodeId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("RPC node not found"));
    }

    private RpcStored requireRpcStored(ChainView chain, long nodeId) {
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                    select id, environment, node_label, purpose, connection_type, rpc_url,
                           auth_type, auth_header_name, api_key, username, password, priority,
                           min_request_interval_ms, enabled, renewal_due_at, remark
                      from chain_rpc_node where id = ? and chain = ? and network = ?
                    """, nodeId, chain.chain(), chain.network());
            return new RpcStored(longValue(row.get("id")), string(row.get("environment")),
                    string(row.get("node_label")), string(row.get("purpose")),
                    string(row.get("connection_type")), string(row.get("rpc_url")),
                    string(row.get("auth_type")), nullable(row.get("auth_header_name")),
                    nullable(row.get("api_key")), nullable(row.get("username")), nullable(row.get("password")),
                    intValue(row.get("priority")), intValue(row.get("min_request_interval_ms")),
                    bool(row.get("enabled")), instant(row.get("renewal_due_at")), nullable(row.get("remark")));
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("RPC node not found for chain profile");
        }
    }

    private TokenView requireToken(long id) {
        return listTokensInternal("", "", null).stream().filter(token -> token.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("token not found"));
    }

    private void validateCanEnable(String chain, String network, Long currentId) {
        if (WalletEnvironmentPolicy.isProduction(environment)
                && !WalletEnvironmentPolicy.isProductionNetwork(network)) {
            throw new IllegalStateException("production environment can enable only a production network");
        }
        jdbc.query("select pg_advisory_xact_lock(hashtext(upper(?)))", resultSet -> null, chain);
        Long enabled = jdbc.queryForObject("""
                select count(*) from chain_profile
                 where upper(chain) = upper(?) and enabled = true
                   and (cast(? as bigint) is null or id <> ?)
                """, Long.class, chain, currentId, currentId);
        if (enabled != null && enabled > 0) {
            throw new IllegalStateException("only one network can be enabled per chain at a time");
        }
        boolean hasTokens = count("select count(*) from token_config where upper(chain) = upper(?)", chain) > 0;
        for (String purpose : WalletRpcPolicy.requiredPurposes(chain, network, hasTokens)) {
            List<Map<String, Object>> nodes = jdbc.queryForList("""
                    select environment, node_label, purpose, connection_type, rpc_url, auth_type,
                           auth_header_name, api_key, username, password, priority,
                           min_request_interval_ms, enabled, renewal_due_at, remark
                      from chain_rpc_node
                     where upper(chain) = upper(?) and lower(network) = lower(?)
                       and lower(environment) = lower(?) and lower(purpose) = lower(?) and enabled = true
                    """, chain, network, environment, purpose);
            if (nodes.isEmpty()) {
                throw new IllegalStateException("configure an enabled " + purpose
                        + " RPC node for environment " + environment + " before enabling this chain");
            }
            nodes.forEach(row -> validateRpc(new RpcValues(
                    string(row.get("environment")), string(row.get("node_label")),
                    string(row.get("purpose")), string(row.get("connection_type")),
                    string(row.get("rpc_url")), string(row.get("auth_type")),
                    nullable(row.get("auth_header_name")), nullable(row.get("api_key")),
                    nullable(row.get("username")), nullable(row.get("password")),
                    intValue(row.get("priority")), intValue(row.get("min_request_interval_ms")),
                    true, instant(row.get("renewal_due_at")), nullable(row.get("remark")))));
        }
    }

    private void validateCanDisableRpc(ChainView chain, long nodeId, String purpose, String nodeEnvironment) {
        if (!chain.enabled() || !WalletRpcPolicy.requiredPurposes(
                chain.chain(), chain.network(), chain.tokenCount() > 0).contains(normalizeLower(purpose))
                || !environment.equalsIgnoreCase(nodeEnvironment)) {
            return;
        }
        long alternatives = count("""
                select count(*) from chain_rpc_node
                 where upper(chain) = upper(?) and lower(network) = lower(?)
                   and lower(environment) = lower(?) and lower(purpose) = lower(?)
                   and enabled = true and id <> ?
                """, chain.chain(), chain.network(), environment, purpose, nodeId);
        if (alternatives == 0) {
            throw new IllegalStateException("an enabled chain must retain an enabled RPC node for required purpose " + purpose);
        }
    }

    private void validateChainValues(ChainValues value) {
        required(value.chain(), "chain");
        required(value.network(), "network");
        required(value.family(), "family");
        required(value.nativeSymbol(), "nativeSymbol");
        if (value.runtimeCurrencyId() < 0 || value.bip44CoinType() < 0) {
            throw new IllegalArgumentException("runtimeCurrencyId and bip44CoinType must not be negative");
        }
        if (value.depositConfirmations() < 0 || value.withdrawConfirmations() < 0
                || value.scanBatchSize() < 1 || value.scanStartHeight() < 0
                || value.scanMaxBlocksPerRun() < 0) {
            throw new IllegalArgumentException("chain numeric settings are invalid");
        }
    }

    private void validateRpc(RpcValues value) {
        required(value.environment(), "environment");
        required(value.nodeLabel(), "nodeLabel");
        required(value.purpose(), "purpose");
        required(value.connectionType(), "connectionType");
        required(value.rpcUrl(), "rpcUrl");
        try {
            URI uri = URI.create(value.rpcUrl());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!List.of("http", "https", "ws", "wss").contains(scheme)
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("rpcUrl must be an absolute HTTP or WebSocket URL");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("rpcUrl must be an absolute HTTP or WebSocket URL");
        }
        if (WalletRpcPolicy.containsPlaceholder(value.rpcUrl())
                || WalletRpcPolicy.containsPlaceholder(value.apiKey())
                || WalletRpcPolicy.containsPlaceholder(value.username())
                || WalletRpcPolicy.containsPlaceholder(value.password())) {
            throw new IllegalArgumentException("RPC URL and credentials cannot contain placeholders");
        }
        if (value.enabled() && WalletRpcPolicy.requiresApiKey(value.authType(), value.connectionType())
                && blank(value.apiKey())) {
            throw new IllegalArgumentException("API key is required by the selected RPC authentication");
        }
        if (value.enabled() && WalletRpcPolicy.requiresUsernamePassword(value.authType())
                && (blank(value.username()) || blank(value.password()))) {
            throw new IllegalArgumentException("username and password are required by the selected RPC authentication");
        }
        if (value.priority() < 0 || value.minRequestIntervalMs() < 0) {
            throw new IllegalArgumentException("RPC priority and request interval must not be negative");
        }
    }

    private void validateToken(TokenValues value) {
        required(value.chain(), "chain");
        required(value.network(), "network");
        required(value.symbol(), "symbol");
        required(value.standard(), "standard");
        required(value.contractAddress(), "contractAddress");
        if (WalletRpcPolicy.containsPlaceholder(value.contractAddress())) {
            throw new IllegalArgumentException("contractAddress cannot contain a placeholder");
        }
        if (value.decimals() < 0 || value.decimals() > 38) {
            throw new IllegalArgumentException("decimals must be between 0 and 38");
        }
        if (!value.enabled() && value.collectEnabled()) {
            throw new IllegalArgumentException("collection cannot be enabled while the token is disabled");
        }
        long profiles = count("""
                select count(*) from chain_profile
                 where upper(chain) = upper(?) and lower(network) = lower(?)
                """, value.chain(), value.network());
        if (profiles == 0) {
            throw new IllegalArgumentException("token must reference an existing chain and network");
        }
        if (value.enabled()) {
            long active = count("""
                    select count(*) from chain_profile
                     where upper(chain) = upper(?) and lower(network) = lower(?) and enabled = true
                    """, value.chain(), value.network());
            if (active == 0) {
                throw new IllegalStateException("enable the matching chain network before enabling this token");
            }
        }
    }

    private void upsertAsset(TokenValues value) {
        jdbc.update("""
                insert into chain_asset(
                    chain, symbol, asset_kind, contract_address, decimals, native_asset,
                    active, min_transfer, min_withdraw, updated_at)
                values (?, ?, ?, ?, ?, false, ?, ?, ?, now())
                on conflict (chain, symbol) do update
                   set asset_kind = excluded.asset_kind, contract_address = excluded.contract_address,
                       decimals = excluded.decimals, native_asset = false, active = excluded.active,
                       min_transfer = excluded.min_transfer, min_withdraw = excluded.min_withdraw,
                       updated_at = now()
                """, value.chain(), value.symbol(), value.standard(), value.contractAddress(),
                value.decimals(), value.enabled(), value.minWithdraw(), value.minWithdraw());
    }

    private void setAssetActive(String chain, String symbol, boolean active) {
        jdbc.update("update chain_asset set active = ?, updated_at = now() where chain = ? and symbol = ? and native_asset = false",
                active, chain, symbol);
    }

    private List<String> chainChecks(ChainView chain, List<RpcNodeView> rpcNodes, List<TokenView> tokens) {
        LinkedHashSet<String> checks = new LinkedHashSet<>();
        if (WalletEnvironmentPolicy.isProduction(environment)
                && !WalletEnvironmentPolicy.isProductionNetwork(chain.network())) {
            checks.add("This network cannot be enabled in production.");
        }
        if (chain.enabled()) {
            for (String purpose : WalletRpcPolicy.requiredPurposes(
                    chain.chain(), chain.network(), !tokens.isEmpty())) {
                boolean present = rpcNodes.stream().anyMatch(node -> node.enabled()
                        && node.environment().equalsIgnoreCase(environment)
                        && node.purpose().equalsIgnoreCase(purpose));
                if (!present) {
                    checks.add("Missing enabled " + purpose + " RPC node for " + environment + ".");
                }
            }
        }
        return List.copyOf(checks);
    }

    private ChainValues mergeChain(ChainView current, ChainCommand command) {
        if (command == null) throw new IllegalArgumentException("chain body is required");
        return new ChainValues(
                upper(or(command.chain(), current == null ? null : current.chain())),
                lower(or(command.network(), current == null ? null : current.network())),
                upper(or(command.family(), current == null ? null : current.family())),
                or(command.runtimeCurrencyId(), current == null ? null : current.runtimeCurrencyId(), 0),
                or(command.bip44CoinType(), current == null ? null : current.bip44CoinType(), 0),
                upper(or(command.nativeSymbol(), current == null ? null : current.nativeSymbol())),
                or(command.explorerUrl(), current == null ? null : current.explorerUrl()),
                or(command.depositConfirmations(), current == null ? null : current.depositConfirmations(), 1),
                or(command.withdrawConfirmations(), current == null ? null : current.withdrawConfirmations(), 1),
                or(command.defaultFeeRate(), current == null ? null : current.defaultFeeRate()),
                or(command.dustThreshold(), current == null ? null : current.dustThreshold()),
                or(command.enabled(), current == null ? null : current.enabled(), false),
                or(command.chainId(), current == null ? null : current.chainId()),
                or(command.gasPolicy(), current == null ? null : current.gasPolicy()),
                or(command.scanBatchSize(), current == null ? null : current.scanBatchSize(), 100),
                or(command.scanEnabled(), current == null ? null : current.scanEnabled(), false),
                or(command.withdrawEnabled(), current == null ? null : current.withdrawEnabled(), false),
                or(command.collectionEnabled(), current == null ? null : current.collectionEnabled(), false),
                or(command.transferEnabled(), current == null ? null : current.transferEnabled(), false),
                or(command.scanStartHeight(), current == null ? null : current.scanStartHeight(), 0L),
                or(command.scanMaxBlocksPerRun(), current == null ? null : current.scanMaxBlocksPerRun(), 0L));
    }

    private RpcValues mergeRpc(RpcStored current, RpcNodeCommand command, ChainView chain) {
        if (command == null) throw new IllegalArgumentException("RPC body is required");
        String authType = upper(or(command.authType(), current == null ? "NONE" : current.authType()));
        String connectionType = upper(or(command.connectionType(),
                current == null ? "HTTP_JSON_RPC" : current.connectionType()));
        String apiKey = secret(command.apiKey(), current == null ? null : current.apiKey());
        String username = secret(command.username(), current == null ? null : current.username());
        String password = secret(command.password(), current == null ? null : current.password());
        if ("NONE".equals(authType) && !"BLOCKFROST".equals(connectionType)) {
            apiKey = null;
            username = null;
            password = null;
        } else if (WalletRpcPolicy.requiresApiKey(authType, connectionType)) {
            username = null;
            password = null;
        } else if (WalletRpcPolicy.requiresUsernamePassword(authType)) {
            apiKey = null;
        }
        return new RpcValues(lower(or(command.environment(), current == null ? environment : current.environment())),
                or(command.nodeLabel(), current == null ? null : current.nodeLabel()),
                lower(or(command.purpose(), current == null ? "rpc" : current.purpose())),
                connectionType,
                or(command.rpcUrl(), current == null ? null : current.rpcUrl()), authType,
                or(command.authHeaderName(), current == null ? null : current.authHeaderName()),
                apiKey, username, password,
                or(command.priority(), current == null ? null : current.priority(), 100),
                or(command.minRequestIntervalMs(), current == null ? null : current.minRequestIntervalMs(), 0),
                or(command.enabled(), current == null ? null : current.enabled(), false),
                or(command.renewalDueAt(), current == null ? null : current.renewalDueAt()),
                or(command.remark(), current == null ? null : current.remark()));
    }

    private TokenValues mergeToken(TokenView current, TokenCommand command) {
        if (command == null) throw new IllegalArgumentException("token body is required");
        return new TokenValues(upper(or(command.chain(), current == null ? null : current.chain())),
                lower(or(command.network(), current == null ? null : current.network())),
                upper(or(command.symbol(), current == null ? null : current.symbol())),
                upper(or(command.standard(), current == null ? null : current.standard())),
                or(command.contractAddress(), current == null ? null : current.contractAddress()),
                or(command.contractAddressBase58(), current == null ? null : current.contractAddressBase58()),
                or(command.contractAddressHex(), current == null ? null : current.contractAddressHex()),
                or(command.decimals(), current == null ? null : current.decimals(), 18),
                or(command.enabled(), current == null ? null : current.enabled(), false),
                or(command.collectEnabled(), current == null ? null : current.collectEnabled(), false),
                or(command.minDeposit(), current == null ? null : current.minDeposit()),
                or(command.minWithdraw(), current == null ? null : current.minWithdraw()),
                or(command.collectThreshold(), current == null ? null : current.collectThreshold()),
                or(command.gasStrategy(), current == null ? null : current.gasStrategy()),
                or(command.confirmationRequired(), current == null ? null : current.confirmationRequired()));
    }

    private ChainView chainView(Map<String, Object> row) {
        return new ChainView(longValue(row.get("id")), string(row.get("chain")), string(row.get("network")),
                string(row.get("family")), intValue(row.get("runtime_currency_id")),
                intValue(row.get("bip44_coin_type")), string(row.get("native_symbol")),
                nullable(row.get("explorer_url")), intValue(row.get("deposit_confirmations")),
                intValue(row.get("withdraw_confirmations")), nullableLong(row.get("default_fee_rate")),
                nullableLong(row.get("dust_threshold")), bool(row.get("enabled")), nullableLong(row.get("chain_id")),
                nullable(row.get("gas_policy")), intValue(row.get("scan_batch_size")),
                bool(row.get("scan_enabled")), bool(row.get("withdraw_enabled")),
                bool(row.get("collection_enabled")), bool(row.get("transfer_enabled")),
                longValue(row.get("scan_start_height")), longValue(row.get("scan_max_blocks_per_run")),
                splitCsv(string(row.get("token_symbols"))),
                intValue(row.get("token_count")), intValue(row.get("rpc_count")),
                instant(row.get("created_at")), instant(row.get("updated_at")));
    }

    private RpcNodeView rpcView(Map<String, Object> row) {
        return new RpcNodeView(longValue(row.get("id")), string(row.get("environment")),
                string(row.get("node_label")), string(row.get("purpose")),
                string(row.get("connection_type")), string(row.get("rpc_url")),
                string(row.get("auth_type")), nullable(row.get("auth_header_name")),
                !blank(nullable(row.get("api_key"))), !blank(nullable(row.get("username"))),
                !blank(nullable(row.get("password"))), intValue(row.get("priority")),
                intValue(row.get("min_request_interval_ms")), bool(row.get("enabled")),
                instant(row.get("renewal_due_at")), nullable(row.get("remark")),
                instant(row.get("last_checked_at")), nullableLong(row.get("last_latency_ms")),
                nullableInteger(row.get("last_http_status")), nullable(row.get("last_error")),
                instant(row.get("created_at")), instant(row.get("updated_at")));
    }

    private TokenView tokenView(Map<String, Object> row, RuntimeSwitches runtime) {
        boolean configured = bool(row.get("enabled"));
        boolean assetActive = bool(row.get("asset_active"));
        boolean chainEnabled = bool(row.get("chain_enabled"));
        boolean anyTaskEnabled = (runtime.scan() && bool(row.get("chain_scan_enabled")))
                || (runtime.withdraw() && bool(row.get("chain_withdraw_enabled")))
                || (runtime.collection() && bool(row.get("chain_collection_enabled"))
                    && bool(row.get("collect_enabled")))
                || (runtime.transfer() && bool(row.get("chain_transfer_enabled")));
        List<String> blockers = new ArrayList<>();
        if (!runtime.wallet()) blockers.add("Wallet master switch is off.");
        if (!configured) blockers.add("Token is disabled.");
        if (!assetActive) blockers.add("Chain asset is disabled.");
        if (!chainEnabled) blockers.add("Chain network is disabled.");
        if (chainEnabled && !anyTaskEnabled) blockers.add("No wallet task is effectively enabled for this chain.");
        return new TokenView(longValue(row.get("id")), string(row.get("chain")),
                string(row.get("network")), string(row.get("symbol")), string(row.get("standard")),
                string(row.get("contract_address")), nullable(row.get("contract_address_base58")),
                nullable(row.get("contract_address_hex")), intValue(row.get("decimals")), configured,
                bool(row.get("collect_enabled")), decimal(row.get("min_deposit")),
                decimal(row.get("min_withdraw")), decimal(row.get("collect_threshold")),
                nullable(row.get("gas_strategy")), nullableInteger(row.get("confirmation_required")),
                assetActive, chainEnabled,
                runtime.wallet() && configured && assetActive && chainEnabled && anyTaskEnabled,
                List.copyOf(blockers), instant(row.get("created_at")), instant(row.get("updated_at")));
    }

    private boolean chainHasRuntimeData(String chain) {
        return count("select count(*) from chain_address where upper(chain) = upper(?)", chain) > 0
                || count("select count(*) from ledger_balance where upper(chain) = upper(?)", chain) > 0
                || count("select count(*) from deposit_record where upper(chain) = upper(?)", chain) > 0
                || count("select count(*) from withdrawal_order where upper(chain) = upper(?)", chain) > 0
                || count("select count(*) from collection_record where upper(chain) = upper(?)", chain) > 0;
    }

    private RuntimeSwitches loadRuntimeSwitches() {
        Map<String, Boolean> values = new java.util.HashMap<>();
        jdbc.queryForList("""
                select config_key, config_value, enabled from wallet_system_config
                 where config_key in ('global.all.enabled', 'global.scan.enabled',
                    'global.withdraw.enabled', 'global.collection.enabled', 'global.transfer.enabled')
                """).forEach(row -> values.put(string(row.get("config_key")),
                bool(row.get("enabled")) && Boolean.parseBoolean(string(row.get("config_value")))));
        return new RuntimeSwitches(
                values.getOrDefault("global.all.enabled", true),
                values.getOrDefault("global.scan.enabled", true),
                values.getOrDefault("global.withdraw.enabled", true),
                values.getOrDefault("global.collection.enabled", true),
                values.getOrDefault("global.transfer.enabled", true));
    }

    private boolean tokenHasRuntimeData(String chain, String symbol) {
        return count("select count(*) from chain_address where upper(chain) = upper(?) and upper(asset_symbol) = upper(?)",
                chain, symbol) > 0
                || count("select count(*) from ledger_balance where upper(chain) = upper(?) and upper(asset_symbol) = upper(?)", chain, symbol) > 0
                || count("select count(*) from deposit_record where upper(chain) = upper(?) and upper(asset_symbol) = upper(?)", chain, symbol) > 0
                || count("select count(*) from withdrawal_order where upper(chain) = upper(?) and upper(asset_symbol) = upper(?)", chain, symbol) > 0
                || count("select count(*) from collection_record where upper(chain) = upper(?) and upper(asset_symbol) = upper(?)", chain, symbol) > 0;
    }

    private void applyAuth(HttpRequest.Builder builder, RpcStored node) {
        String auth = upper(node.authType());
        if ("BLOCKFROST".equalsIgnoreCase(node.connectionType()) && !blank(node.apiKey())) {
            builder.header("project_id", node.apiKey());
        } else if ("BEARER".equals(auth) && !blank(node.apiKey())) {
            builder.header("Authorization", "Bearer " + node.apiKey());
        } else if (("API_KEY".equals(auth) || "PROJECT_ID".equals(auth) || "TOKEN".equals(auth)
                || "API_KEY_OPTIONAL".equals(auth))
                && !blank(node.apiKey())) {
            builder.header(blank(node.authHeaderName()) ? "X-API-Key" : node.authHeaderName(), node.apiKey());
        } else if (("BASIC".equals(auth) || "DIGEST".equals(auth)) && !blank(node.username())) {
            String value = java.util.Base64.getEncoder().encodeToString(
                    (node.username() + ":" + node.password()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + value);
        }
    }

    private RpcTestView saveRpcTest(long nodeId, RpcTestView result) {
        jdbc.update("""
                update chain_rpc_node
                   set last_checked_at = ?, last_latency_ms = ?, last_http_status = ?, last_error = ?
                 where id = ?
                """, java.sql.Timestamp.from(result.checkedAt()), result.latencyMs(),
                result.statusCode(), result.error(), nodeId);
        return result;
    }

    private void applyAuth(WebSocket.Builder builder, RpcStored node) {
        String auth = upper(node.authType());
        if ("BEARER".equals(auth) && !blank(node.apiKey())) {
            builder.header("Authorization", "Bearer " + node.apiKey());
        } else if (("API_KEY".equals(auth) || "PROJECT_ID".equals(auth) || "TOKEN".equals(auth)
                || "API_KEY_OPTIONAL".equals(auth))
                && !blank(node.apiKey())) {
            builder.header(blank(node.authHeaderName()) ? "X-API-Key" : node.authHeaderName(), node.apiKey());
        } else if ("BASIC".equals(auth) && !blank(node.username())) {
            String value = java.util.Base64.getEncoder().encodeToString(
                    (node.username() + ":" + node.password()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + value);
        }
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private void audit(CustodyPrincipal actor, String action, String resourceType,
                       String resourceId, String sourceIp, String details) {
        custodyRepository.audit(null, "PLATFORM_USER", actor.actorId().toString(), action,
                resourceType, resourceId, sourceIp, details);
    }

    private static void requirePlatformAdmin(CustodyPrincipal actor) {
        if (actor == null || !actor.isPlatformAdmin() || actor.tenantId() != null) {
            throw new CustodyForbiddenException("platform administrator access is required");
        }
    }

    private static String rpcAudit(RpcValues value) {
        return "{\"environment\":" + json(value.environment()) + ",\"purpose\":"
                + json(value.purpose()) + ",\"enabled\":" + value.enabled()
                + ",\"credentialsConfigured\":"
                + (!blank(value.apiKey()) || !blank(value.username()) || !blank(value.password())) + "}";
    }

    private static String tokenAudit(TokenValues value) {
        return "{\"chain\":" + json(value.chain()) + ",\"network\":" + json(value.network())
                + ",\"symbol\":" + json(value.symbol()) + ",\"enabled\":" + value.enabled() + "}";
    }

    private static String json(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static void required(String value, String field) {
        if (blank(value)) throw new IllegalArgumentException(field + " is required");
    }

    private static String secret(String requested, String current) {
        return requested == null || requested.isBlank() ? current : requested;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String nullable(Object value) { return value == null ? null : String.valueOf(value); }
    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }
    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String normalizeLower(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String upper(String value) { return value == null ? null : value.trim().toUpperCase(Locale.ROOT); }
    private static String lower(String value) { return value == null ? null : value.trim().toLowerCase(Locale.ROOT); }
    private static List<String> splitCsv(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }
    private static boolean bool(Object value) { return value instanceof Boolean b ? b : Boolean.parseBoolean(string(value)); }
    private static int intValue(Object value) { return value == null ? 0 : ((Number) value).intValue(); }
    private static Integer nullableInteger(Object value) { return value == null ? null : ((Number) value).intValue(); }
    private static long longValue(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private static Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant result) return result;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        return null;
    }
    private static <T> T or(T requested, T current) { return requested == null ? current : requested; }
    private static int or(Integer requested, Integer current, int fallback) {
        return requested != null ? requested : current != null ? current : fallback;
    }
    private static long or(Long requested, Long current, long fallback) {
        return requested != null ? requested : current != null ? current : fallback;
    }
    private static boolean or(Boolean requested, Boolean current, boolean fallback) {
        return requested != null ? requested : current != null ? current : fallback;
    }

    private static final String CHAIN_SELECT = """
            select p.id, p.chain, p.network, p.family, p.runtime_currency_id, p.bip44_coin_type,
                   p.native_symbol, p.explorer_url, p.deposit_confirmations, p.withdraw_confirmations,
                   p.default_fee_rate, p.dust_threshold, p.enabled, p.chain_id, p.gas_policy,
                   p.scan_batch_size, p.scan_enabled, p.withdraw_enabled, p.collection_enabled,
                   p.transfer_enabled, p.scan_start_height, p.scan_max_blocks_per_run,
                   coalesce((select string_agg(t.symbol, ',' order by t.symbol)
                               from token_config t where t.chain = p.chain and t.network = p.network), '') as token_symbols,
                   (select count(*) from token_config t where t.chain = p.chain and t.network = p.network) as token_count,
                   (select count(*) from chain_rpc_node r where r.chain = p.chain and r.network = p.network) as rpc_count,
                   p.created_at, p.updated_at
              from chain_profile p
            """;

    public record ChainCommand(String chain, String network, String family, Integer runtimeCurrencyId,
                               Integer bip44CoinType, String nativeSymbol, String explorerUrl,
                               Integer depositConfirmations, Integer withdrawConfirmations,
                               Long defaultFeeRate, Long dustThreshold, Boolean enabled, Long chainId,
                               String gasPolicy, Integer scanBatchSize, Boolean scanEnabled,
                               Boolean withdrawEnabled, Boolean collectionEnabled, Boolean transferEnabled,
                               Long scanStartHeight, Long scanMaxBlocksPerRun) {}
    public record ChainSwitchCommand(Boolean enabled, Boolean scanEnabled, Boolean withdrawEnabled,
                                     Boolean collectionEnabled, Boolean transferEnabled) {}
    public record ChainView(long id, String chain, String network, String family, int runtimeCurrencyId,
                            int bip44CoinType, String nativeSymbol, String explorerUrl,
                            int depositConfirmations, int withdrawConfirmations, Long defaultFeeRate,
                            Long dustThreshold, boolean enabled, Long chainId, String gasPolicy,
                            int scanBatchSize, boolean scanEnabled, boolean withdrawEnabled,
                            boolean collectionEnabled, boolean transferEnabled, long scanStartHeight,
                            long scanMaxBlocksPerRun, List<String> tokenSymbols, int tokenCount, int rpcCount,
                            Instant createdAt, Instant updatedAt) {}
    public record ChainDetailView(ChainView chain, List<RpcNodeView> rpcNodes, List<TokenView> tokens,
                                  List<String> checks, boolean production, String environment) {}
    private record ChainValues(String chain, String network, String family, int runtimeCurrencyId,
                               int bip44CoinType, String nativeSymbol, String explorerUrl,
                               int depositConfirmations, int withdrawConfirmations, Long defaultFeeRate,
                               Long dustThreshold, boolean enabled, Long chainId, String gasPolicy,
                               int scanBatchSize, boolean scanEnabled, boolean withdrawEnabled,
                               boolean collectionEnabled, boolean transferEnabled, long scanStartHeight,
                               long scanMaxBlocksPerRun) {}

    public record RpcNodeCommand(String environment, String nodeLabel, String purpose,
                                 String connectionType, String rpcUrl, String authType,
                                 String authHeaderName, String apiKey, String username, String password,
                                 Integer priority, Integer minRequestIntervalMs, Boolean enabled,
                                 Instant renewalDueAt, String remark) {}
    public record RpcNodeView(long id, String environment, String nodeLabel, String purpose,
                              String connectionType, String rpcUrl, String authType,
                              String authHeaderName, boolean apiKeyConfigured,
                              boolean usernameConfigured, boolean passwordConfigured,
                              int priority, int minRequestIntervalMs, boolean enabled,
                              Instant renewalDueAt, String remark, Instant lastCheckedAt,
                              Long lastLatencyMs, Integer lastHttpStatus, String lastError,
                              Instant createdAt, Instant updatedAt) {}
    public record RpcTestView(boolean success, Integer statusCode, long latencyMs,
                              String error, Instant checkedAt) {}
    private record RpcStored(long id, String environment, String nodeLabel, String purpose,
                             String connectionType, String rpcUrl, String authType,
                             String authHeaderName, String apiKey, String username, String password,
                             int priority, int minRequestIntervalMs, boolean enabled,
                             Instant renewalDueAt, String remark) {}
    private record RpcValues(String environment, String nodeLabel, String purpose,
                             String connectionType, String rpcUrl, String authType,
                             String authHeaderName, String apiKey, String username, String password,
                             int priority, int minRequestIntervalMs, boolean enabled,
                             Instant renewalDueAt, String remark) {}

    public record TokenCommand(String chain, String network, String symbol, String standard,
                               String contractAddress, String contractAddressBase58,
                               String contractAddressHex, Integer decimals, Boolean enabled,
                               Boolean collectEnabled, BigDecimal minDeposit, BigDecimal minWithdraw,
                               BigDecimal collectThreshold, String gasStrategy, Integer confirmationRequired) {}
    public record TokenStatusCommand(Boolean enabled) {}
    public record TokenView(long id, String chain, String network, String symbol, String standard,
                            String contractAddress, String contractAddressBase58,
                            String contractAddressHex, int decimals, boolean enabled,
                            boolean collectEnabled, BigDecimal minDeposit, BigDecimal minWithdraw,
                            BigDecimal collectThreshold, String gasStrategy, Integer confirmationRequired,
                            boolean assetActive, boolean chainEnabled, boolean effectiveEnabled,
                            List<String> blockers, Instant createdAt, Instant updatedAt) {}
    private record TokenValues(String chain, String network, String symbol, String standard,
                               String contractAddress, String contractAddressBase58,
                               String contractAddressHex, int decimals, boolean enabled,
                               boolean collectEnabled, BigDecimal minDeposit, BigDecimal minWithdraw,
                               BigDecimal collectThreshold, String gasStrategy, Integer confirmationRequired) {}
    private record RuntimeSwitches(boolean wallet, boolean scan, boolean withdraw,
                                   boolean collection, boolean transfer) {}
}
