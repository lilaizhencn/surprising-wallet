package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.TokenConfiguration;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.model.PageView;
import tools.jackson.databind.JsonNode;
import com.surprising.wallet.config.WalletEnvironmentPolicy;
import com.surprising.wallet.config.WalletRpcPolicy;
import com.surprising.wallet.common.chain.EvmFeeModel;
import com.surprising.wallet.common.chain.EvmGasPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.web3j.utils.Numeric;

import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainRpcNodeRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.DepositRecordRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.service.WalletRuntimeConfigService.GlobalSwitches;
import static com.surprising.wallet.service.WalletRuntimeConfigService.*;
import com.surprising.wallet.repository.WithdrawalOrderRepository;

/**
 * 钱包配置管理服务，管理系统配置项（环境策略、RPC 策略）的 Console CRUD。
 *
 * <p>支持数据库连接测试、RPC 节点连通性探测（HTTP + WebSocket）、
 * 链配置文件管理。所有写操作需平台管理员权限。
 */
@Service
public class WalletConfigManagementService {
    private final WalletRuntimeConfigService runtimeConfig;
    private final ChainProfileRepository chainProfileRepository;
    private final ChainRpcNodeRepository rpcNodeRepository;
    private final ChainAssetRepository chainAssetRepository;
    private final TokenConfigRepository tokenConfigRepository;
    private final ChainAddressRepository chainAddressRepository;
    private final LedgerBalanceRepository ledgerBalanceRepository;
    private final DepositRecordRepository depositRecordRepository;
    private final WithdrawalOrderRepository withdrawalOrderRepository;
    private final CollectionRecordRepository collectionRecordRepository;
    private final CustodyAuditRepository audits;
    private final String environment;
    private final WalletRpcClient rpcClient;

    public WalletConfigManagementService(WalletRuntimeConfigService runtimeConfig,
                                         ChainProfileRepository chainProfileRepository,
                                         ChainRpcNodeRepository rpcNodeRepository,
                                         ChainAssetRepository chainAssetRepository,
                                         TokenConfigRepository tokenConfigRepository,
                                         ChainAddressRepository chainAddressRepository,
                                         LedgerBalanceRepository ledgerBalanceRepository,
                                         DepositRecordRepository depositRecordRepository,
                                         WithdrawalOrderRepository withdrawalOrderRepository,
                                         CollectionRecordRepository collectionRecordRepository,
                                         CustodyAuditRepository audits,
                                         @Value("${sw.app.env.name:dev}")
                                         String environment,
                                         WalletRpcClient rpcClient) {
        this.runtimeConfig = runtimeConfig;
        this.chainProfileRepository = chainProfileRepository;
        this.rpcNodeRepository = rpcNodeRepository;
        this.chainAssetRepository = chainAssetRepository;
        this.tokenConfigRepository = tokenConfigRepository;
        this.chainAddressRepository = chainAddressRepository;
        this.ledgerBalanceRepository = ledgerBalanceRepository;
        this.depositRecordRepository = depositRecordRepository;
        this.withdrawalOrderRepository = withdrawalOrderRepository;
        this.collectionRecordRepository = collectionRecordRepository;
        this.audits = audits;
        this.environment = normalizeLower(environment);
        this.rpcClient = rpcClient;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<ChainView> listChains(CustodyPrincipal actor, String search, Boolean enabled,
                                      String network, String family, String token,
                                      Boolean scanEnabled, Boolean withdrawEnabled,
                                      Boolean collectionEnabled, Boolean transferEnabled) {
        requirePlatformAdmin(actor);
        String query = normalize(search);
        String networkFilter = normalize(network);
        String familyFilter = normalize(family);
        String tokenFilter = normalize(token);
        return chainRows().stream()
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
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ChainDetailView getChain(CustodyPrincipal actor, long id) {
        requirePlatformAdmin(actor);
        AccountChainProfile profile = requireChain(id);
        List<RpcNodeView> rpcNodes = listRpcNodesInternal(profile);
        var configured = tokenConfigRepository.listByChainAndNetwork(profile.getChain(), profile.getNetwork());
        var assets = chainAssetRepository.listDefinitionsByChain(profile.getChain()).stream().collect(Collectors.toMap(
                row -> new AssetKey(row.getChain(), row.getSymbol()), row -> row));
        var runtime = runtimeConfig.globalSwitches();
        List<TokenView> tokens = configured.stream()
                .sorted(java.util.Comparator.comparing(row -> normalize(row.symbol())))
                .map(row -> tokenView(row, runtime, assets.get(new AssetKey(row.chain(), row.symbol())), profile)).toList();
        ChainView chain = chainView(profile, configured.stream().map(TokenConfiguration::symbol).sorted().toList(), rpcNodes.size());
        return new ChainDetailView(chain, rpcNodes, tokens, chainChecks(chain, rpcNodes, tokens),
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
        long id = chainProfileRepository.insert(
                values.chain(), values.network(), values.family(), values.runtimeCurrencyId(),
                values.bip44CoinType(), values.nativeSymbol(), values.explorerUrl(),
                values.depositConfirmations(), values.withdrawConfirmations(), values.defaultFeeRate(),
                values.dustThreshold(), values.enabled(), values.chainId(), values.gasPolicy(),
                values.feeModel(), values.scanBatchSize(), values.scanEnabled(), values.withdrawEnabled(),
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
        AccountChainProfile current = requireChain(id);
        ChainValues values = mergeChain(current, command);
        validateChainValues(values);
        if ((!current.getChain().equalsIgnoreCase(values.chain())
                || !current.getNetwork().equalsIgnoreCase(values.network())
                || current.getRuntimeCurrencyId() != values.runtimeCurrencyId()
                || current.getBip44CoinType() != values.bip44CoinType()) && chainHasRuntimeData(current.getChain())) {
            throw new IllegalStateException("chain identity, network, runtime currency and BIP44 coin type are locked after addresses or transactions exist");
        }
        if (values.enabled()) {
            validateCanEnable(values.chain(), values.network(), id);
        }
        chainProfileRepository.update(id, values.chain(), values.network(), values.family(),
                values.runtimeCurrencyId(), values.bip44CoinType(), values.nativeSymbol(),
                values.explorerUrl(), values.depositConfirmations(), values.withdrawConfirmations(),
                values.defaultFeeRate(), values.dustThreshold(), values.enabled(), values.chainId(),
                values.gasPolicy(), values.feeModel(), values.scanBatchSize(), values.scanEnabled(),
                values.withdrawEnabled(), values.collectionEnabled(), values.transferEnabled(),
                values.scanStartHeight(), values.scanMaxBlocksPerRun());
        if (!current.getChain().equalsIgnoreCase(values.chain())
                || !current.getNetwork().equalsIgnoreCase(values.network())) {
            rpcNodeRepository.moveChain(current.getChain(), current.getNetwork(), values.chain(), values.network());
            tokenConfigRepository.moveChain(current.getChain(), current.getNetwork(), values.chain(), values.network());
            if (!current.getChain().equalsIgnoreCase(values.chain())) {
                chainAssetRepository.moveChain(current.getChain(), values.chain());
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
        AccountChainProfile current = requireChain(id);
        if (command == null || command.enabled() == null || command.scanEnabled() == null
                || command.withdrawEnabled() == null || command.collectionEnabled() == null
                || command.transferEnabled() == null) {
            throw new IllegalArgumentException("all chain switches are required");
        }
        if (command.enabled()) {
            validateCanEnable(current.getChain(), current.getNetwork(), id);
        }
        chainProfileRepository.updateSwitches(id, command.enabled(), command.scanEnabled(),
                command.withdrawEnabled(), command.collectionEnabled(), command.transferEnabled());
        audit(actor, "WALLET_CHAIN.SWITCHES_UPDATE", "CHAIN_PROFILE", String.valueOf(id), sourceIp,
                "{\"enabled\":" + command.enabled() + "}");
        return getChain(actor, id);
    }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<RpcNodeView> listRpcNodes(CustodyPrincipal actor, long chainId) {
        requirePlatformAdmin(actor);
        return listRpcNodesInternal(requireChain(chainId));
    }

    @Transactional
    public RpcNodeView createRpcNode(CustodyPrincipal actor, long chainId,
                                     RpcNodeCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        AccountChainProfile chain = requireChain(chainId);
        ChainRpcNode values = mergeRpc(null, command);
        validateRpc(values);
        long id = rpcNodeRepository.insert(
                chain.getChain(), chain.getNetwork(), values.getEnvironment(), values.getNodeLabel(), values.getPurpose(),
                values.getConnectionType(), values.getRpcUrl(), values.getAuthType(), values.getAuthHeaderName(),
                values.getApiKey(), values.getUsername(), values.getPassword(), values.getPriority(),
                values.getMinRequestIntervalMs(), values.getEnabled(), values.getRenewalDueAt(), values.getRemark());
        audit(actor, "WALLET_RPC.CREATE", "CHAIN_RPC_NODE", String.valueOf(id), sourceIp,
                rpcAudit(values));
        return rpcView(requireRpcNode(chain, id));
    }

    @Transactional
    public RpcNodeView updateRpcNode(CustodyPrincipal actor, long chainId, long nodeId,
                                     RpcNodeCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        AccountChainProfile chain = requireChain(chainId);
        ChainRpcNode current = requireRpcNode(chain, nodeId);
        ChainRpcNode values = mergeRpc(current, command);
        validateRpc(values);
        if (current.getEnabled() && (!values.getEnabled()
                || !current.getPurpose().equalsIgnoreCase(values.getPurpose())
                || !current.getEnvironment().equalsIgnoreCase(values.getEnvironment()))) {
            validateCanDisableRpc(chain, nodeId, current.getPurpose(), current.getEnvironment());
        }
        rpcNodeRepository.update(nodeId, chain.getChain(), chain.getNetwork(), values.getEnvironment(),
                values.getNodeLabel(), values.getPurpose(), values.getConnectionType(), values.getRpcUrl(),
                values.getAuthType(), values.getAuthHeaderName(), values.getApiKey(), values.getUsername(),
                values.getPassword(), values.getPriority(), values.getMinRequestIntervalMs(), values.getEnabled(),
                values.getRenewalDueAt(), values.getRemark());
        audit(actor, "WALLET_RPC.UPDATE", "CHAIN_RPC_NODE", String.valueOf(nodeId), sourceIp,
                rpcAudit(values));
        return rpcView(requireRpcNode(chain, nodeId));
    }

    @Transactional
    public void deleteRpcNode(CustodyPrincipal actor, long chainId, long nodeId, String sourceIp) {
        requirePlatformAdmin(actor);
        AccountChainProfile chain = requireChain(chainId);
        ChainRpcNode node = requireRpcNode(chain, nodeId);
        if (node.getEnabled()) {
            throw new IllegalStateException("disable the RPC node before deleting it");
        }
        rpcNodeRepository.delete(nodeId, chain.getChain(), chain.getNetwork());
        audit(actor, "WALLET_RPC.DELETE", "CHAIN_RPC_NODE", String.valueOf(nodeId), sourceIp,
                "{\"chain\":" + json(chain.getChain()) + ",\"network\":" + json(chain.getNetwork()) + "}");
    }
    public RpcTestView testRpcNode(CustodyPrincipal actor, long chainId, long nodeId) {
        requirePlatformAdmin(actor);
        AccountChainProfile chain = requireChain(chainId);
        ChainRpcNode node = requireRpcNode(chain, nodeId);
        WalletRpcClient.ProbeResult result = rpcClient.probe(
                node.getRpcUrl(), node.getConnectionType(), node.getAuthType(), node.getAuthHeaderName(),
                node.getApiKey(), node.getUsername(), node.getPassword());
        return saveRpcTest(nodeId, new RpcTestView(
                result.success(), result.statusCode(), result.latencyMs(),
                result.error(), result.checkedAt()));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<TokenView> listTokens(CustodyPrincipal actor, String search, String chain,
                                      String network, String standard, Boolean enabled, Boolean effectiveEnabled) {
        requirePlatformAdmin(actor);
        String query = normalize(search);
        String chainFilter = normalize(chain);
        String networkFilter = normalize(network);
        String standardFilter = normalize(standard);
        var configured = chainFilter.isEmpty() ? tokenConfigRepository.listAll() : tokenConfigRepository.listByChain(chainFilter);
        var selected = configured.stream()
                .filter(row -> networkFilter.isEmpty() || normalize(row.network()).equals(networkFilter))
                .filter(row -> standardFilter.isEmpty() || normalize(row.standard()).equals(standardFilter))
                .filter(row -> enabled == null || row.enabled() == enabled)
                .filter(row -> query.isEmpty() || normalize(row.symbol()).contains(query)
                        || normalize(row.contractAddress()).contains(query))
                .sorted(java.util.Comparator.comparing((TokenConfiguration row) -> normalize(row.symbol()))
                        .thenComparing(row -> normalize(row.chain()))).toList();
        if (selected.isEmpty()) return List.of();
        GlobalSwitches runtime = runtimeConfig.globalSwitches();
        var profileRows = chainFilter.isEmpty() ? chainProfileRepository.listAll() : chainProfileRepository.listByChain(chainFilter);
        var profiles = profileRows.stream().collect(Collectors.toMap(
                row -> profileKey(row.getChain(), row.getNetwork()), row -> row, (left, right) -> left));
        var assetRows = chainFilter.isEmpty() ? chainAssetRepository.listDefinitions() : chainAssetRepository.listDefinitionsByChain(chainFilter);
        var assets = assetRows.stream().collect(Collectors.toMap(
                row -> new AssetKey(row.getChain(), row.getSymbol()), row -> row));
        return selected.stream().map(row -> tokenView(row, runtime, assets.get(new AssetKey(row.chain(), row.symbol())),
                        profiles.get(profileKey(row.chain(), row.network()))))
                .filter(row -> effectiveEnabled == null || row.effectiveEnabled() == effectiveEnabled).toList();
    }

    @Transactional
    public TokenView createToken(CustodyPrincipal actor, TokenCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        TokenValues values = mergeToken(null, command);
        validateToken(values);
        long id = tokenConfigRepository.insert(
                values.chain(), values.network(), values.symbol(), values.standard(),
                values.contractAddress(), values.contractAddressBase58(), values.contractAddressHex(),
                values.decimals(), values.enabled(), values.minDeposit(), values.minWithdraw(),
                values.collectEnabled(), values.collectThreshold(), values.gasStrategy(),
                values.confirmationRequired());
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
        tokenConfigRepository.update(id, values.chain(), values.network(), values.symbol(), values.standard(),
                values.contractAddress(), values.contractAddressBase58(), values.contractAddressHex(),
                values.decimals(), values.enabled(), values.minDeposit(), values.minWithdraw(),
                values.collectEnabled(), values.collectThreshold(), values.gasStrategy(),
                values.confirmationRequired());
        if (!current.chain().equalsIgnoreCase(values.chain()) || !current.symbol().equalsIgnoreCase(values.symbol())) {
            chainAssetRepository.deleteNonNative(current.chain(), current.symbol());
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
        tokenConfigRepository.updateEnabled(id, command.enabled(), collectEnabled);
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
    public PageView<Map<String, Object>> auditLog(CustodyPrincipal actor, int limit, int offset) {
        requirePlatformAdmin(actor);
        int pageSize = Math.min(Math.max(limit, 1), 200);
        int pageOffset = Math.max(offset, 0);
        return new PageView<>(
                audits.listPlatform(pageSize, pageOffset),
                audits.countPlatform(),
                pageSize, pageOffset);
    }
    private List<RpcNodeView> listRpcNodesInternal(AccountChainProfile profile) {
        return rpcNodeRepository.listByChain(profile.getChain(), profile.getNetwork()).stream()
                .map(this::rpcView).toList();
    }

    private AccountChainProfile requireChain(long id) {
        return chainProfileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("chain profile not found"));
    }

    private ChainRpcNode requireRpcNode(AccountChainProfile chain, long nodeId) {
        return rpcNodeRepository.findById(nodeId, chain.getChain(), chain.getNetwork())
                .orElseThrow(() -> new IllegalArgumentException("RPC node not found for chain profile"));
    }
    private TokenView requireToken(long id) {
        TokenConfiguration row = tokenConfigRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("token not found"));
        return tokenView(row, runtimeConfig.globalSwitches(),
                chainAssetRepository.find(row.chain(), row.symbol()).orElse(null),
                chainProfileRepository.findByChainAndNetwork(row.chain(), row.network()).orElse(null));
    }

    private void validateCanEnable(String chain, String network, Long currentId) {
        if (WalletEnvironmentPolicy.isProduction(environment)
                && !WalletEnvironmentPolicy.isProductionNetwork(network)) {
            throw new IllegalStateException("production environment can enable only a production network");
        }
        chainProfileRepository.lockChain(chain);
        if (chainProfileRepository.hasOtherEnabledNetwork(chain, currentId)) {
            throw new IllegalStateException("only one network can be enabled per chain at a time");
        }
        boolean hasTokens = tokenConfigRepository.countByChain(chain) > 0;
        for (String purpose : WalletRpcPolicy.requiredPurposes(chain, network, hasTokens)) {
            List<ChainRpcNode> nodes = rpcNodeRepository.listEnabledForPurpose(
                    chain, network, environment, purpose);
            if (nodes.isEmpty()) {
                throw new IllegalStateException("configure an enabled " + purpose
                        + " RPC node for environment " + environment + " before enabling this chain");
            }
            nodes.forEach(this::validateRpc);
        }
    }
    private void validateCanDisableRpc(AccountChainProfile chain, long nodeId, String purpose, String nodeEnvironment) {
        if (!chain.getEnabled() || !WalletRpcPolicy.requiredPurposes(
                chain.getChain(), chain.getNetwork(), tokenConfigRepository.existsByChainAndNetwork(chain.getChain(), chain.getNetwork())).contains(normalizeLower(purpose))
                || !environment.equalsIgnoreCase(nodeEnvironment)) {
            return;
        }
        long alternatives = rpcNodeRepository.countEnabledAlternatives(
                chain.getChain(), chain.getNetwork(), environment, purpose, nodeId);
        if (alternatives == 0) {
            throw new IllegalStateException("an enabled chain must retain an enabled RPC node for required purpose " + purpose);
        }
    }
    private void validateChainValues(ChainValues value) {
        required(value.chain(), "chain");
        required(value.network(), "network");
        required(value.family(), "family");
        required(value.nativeSymbol(), "nativeSymbol");
        if ("EVM".equalsIgnoreCase(value.family())) {
            EvmGasPolicy.parse(value.gasPolicy());
            EvmFeeModel.parse(value.feeModel());
            if (value.chainId() == null || value.chainId() <= 0) {
                throw new IllegalArgumentException("EVM chainId must be positive");
            }
        }
        if (value.runtimeCurrencyId() < 0 || value.bip44CoinType() < 0) {
            throw new IllegalArgumentException("runtimeCurrencyId and bip44CoinType must not be negative");
        }
        if (value.depositConfirmations() < 1 || value.withdrawConfirmations() < 1
                || value.scanBatchSize() < 1 || value.scanStartHeight() < 0
                || value.scanMaxBlocksPerRun() < 0) {
            throw new IllegalArgumentException("chain numeric settings are invalid");
        }
    }
    private void validateRpc(ChainRpcNode value) {
        required(value.getEnvironment(), "environment");
        required(value.getNodeLabel(), "nodeLabel");
        required(value.getPurpose(), "purpose");
        required(value.getConnectionType(), "connectionType");
        required(value.getRpcUrl(), "rpcUrl");
        try {
            URI uri = URI.create(value.getRpcUrl());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!List.of("http", "https", "ws", "wss").contains(scheme)
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("rpcUrl must be an absolute HTTP or WebSocket URL");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("rpcUrl must be an absolute HTTP or WebSocket URL");
        }
        if (WalletRpcPolicy.containsPlaceholder(value.getRpcUrl())
                || WalletRpcPolicy.containsPlaceholder(value.getApiKey())
                || WalletRpcPolicy.containsPlaceholder(value.getUsername())
                || WalletRpcPolicy.containsPlaceholder(value.getPassword())) {
            throw new IllegalArgumentException("RPC URL and credentials cannot contain placeholders");
        }
        if (value.getEnabled() && WalletRpcPolicy.requiresApiKey(value.getAuthType(), value.getConnectionType())
                && blank(value.getApiKey())) {
            throw new IllegalArgumentException("API key is required by the selected RPC authentication");
        }
        if (value.getEnabled() && WalletRpcPolicy.requiresUsernamePassword(value.getAuthType())
                && (blank(value.getUsername()) || blank(value.getPassword()))) {
            throw new IllegalArgumentException("username and password are required by the selected RPC authentication");
        }
        if (value.getPriority() < 0 || value.getMinRequestIntervalMs() < 0) {
            throw new IllegalArgumentException("RPC priority and request interval must not be negative");
        }
    }
    private void validateToken(TokenValues value) {
        required(value.chain(), "chain");
        required(value.network(), "network");
        required(value.symbol(), "symbol");
        required(value.standard(), "standard");
        required(value.contractAddress(), "contractAddress");
        if (!value.symbol().matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw new IllegalArgumentException("symbol must be 2-32 uppercase letters, digits or underscores");
        }
        if (!value.standard().matches("^[A-Z][A-Z0-9_-]{1,31}$")) {
            throw new IllegalArgumentException("standard must be 2-32 uppercase letters, digits, underscores or hyphens");
        }
        if (value.contractAddress().length() > 128
                || value.contractAddress().chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("contractAddress must be at most 128 characters without whitespace");
        }
        if (WalletRpcPolicy.containsPlaceholder(value.contractAddress())) {
            throw new IllegalArgumentException("contractAddress cannot contain a placeholder");
        }
        if (value.decimals() < 0 || value.decimals() > 38) {
            throw new IllegalArgumentException("decimals must be between 0 and 38");
        }
        if (!value.enabled() && value.collectEnabled()) {
            throw new IllegalArgumentException("collection cannot be enabled while the token is disabled");
        }
        validateTokenAmount(value.minDeposit(), "minDeposit", value.decimals());
        validateTokenAmount(value.minWithdraw(), "minWithdraw", value.decimals());
        validateTokenAmount(value.collectThreshold(), "collectThreshold", value.decimals());
        if (value.confirmationRequired() != null
                && (value.confirmationRequired() < 0 || value.confirmationRequired() > 1_000_000)) {
            throw new IllegalArgumentException("confirmationRequired must be between 0 and 1000000");
        }
        if (value.gasStrategy() != null && (value.gasStrategy().length() > 64
                || !value.gasStrategy().matches("^[A-Za-z0-9._:-]*$"))) {
            throw new IllegalArgumentException("gasStrategy contains unsupported characters");
        }
        AccountChainProfile profile = requireTokenChainProfile(value);
        if (value.enabled()) {
            if (!Boolean.TRUE.equals(profile.getEnabled())) {
                throw new IllegalStateException("enable the matching chain network before enabling this token");
            }
            if ((profile.getFamily() != null && profile.getFamily().toUpperCase(Locale.ROOT).contains("EVM"))) {
                validateEvmTokenContract(value, profile);
            }
        }
    }
    private AccountChainProfile requireTokenChainProfile(TokenValues value) {
        return chainProfileRepository.findByChainAndNetwork(value.chain(), value.network())
                .orElseThrow(() -> new IllegalArgumentException("token must reference an existing chain and network"));
    }
    private void validateEvmTokenContract(TokenValues value, AccountChainProfile profile) {
        String address = value.contractAddress();
        if (!address.matches("(?i)^0x[0-9a-f]{40}$")
                || address.matches("(?i)^0x0{40}$")) {
            throw new IllegalArgumentException("EVM contractAddress must be a non-zero 20-byte hex address");
        }
        ChainRpcNode rpc = requireTokenValidationRpc(value.chain(), value.network());
        if (profile.getChainId() != null) {
            BigInteger observedChainId = quantity(evmRpc(rpc, "eth_chainId", "[]"), "chain id");
            if (!observedChainId.equals(BigInteger.valueOf(profile.getChainId()))) {
                throw new IllegalStateException("token validation RPC chain id does not match chain profile");
            }
        }
        String code = textResult(evmRpc(rpc, "eth_getCode",
                "[" + json(address) + ",\"latest\"]"), "contract code");
        if (!code.matches("(?i)^0x[0-9a-f]+$") || code.matches("(?i)^0x0*$")) {
            throw new IllegalArgumentException("contractAddress has no deployed bytecode on the configured network");
        }
        int onChainDecimals = quantity(evmRpc(rpc, "eth_call",
                "[{\"to\":" + json(address) + ",\"data\":\"0x313ce567\"},\"latest\"]"),
                "token decimals").intValueExact();
        if (onChainDecimals != value.decimals()) {
            throw new IllegalArgumentException("configured decimals do not match the token contract");
        }
        String onChainSymbol = abiString(textResult(evmRpc(rpc, "eth_call",
                "[{\"to\":" + json(address) + ",\"data\":\"0x95d89b41\"},\"latest\"]"),
                "token symbol"));
        if (!value.symbol().equalsIgnoreCase(onChainSymbol)) {
            throw new IllegalArgumentException("configured symbol does not match the token contract");
        }
    }
    private ChainRpcNode requireTokenValidationRpc(String chain, String network) {
        return rpcNodeRepository.listTokenValidationNodes(chain, network, environment).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "an enabled HTTP JSON-RPC node is required to validate an EVM token"));
    }
    private JsonNode evmRpc(ChainRpcNode node, String method, String params) {
        return rpcClient.jsonRpc(
                node.getRpcUrl(), node.getConnectionType(), node.getAuthType(), node.getAuthHeaderName(),
                node.getApiKey(), node.getUsername(), node.getPassword(), method, params);
    }
    private static BigInteger quantity(JsonNode result, String field) {
        String value = textResult(result, field);
        if (!value.matches("(?i)^0x[0-9a-f]+$")) {
            throw new IllegalStateException(field + " returned an invalid EVM quantity");
        }
        return new BigInteger(Numeric.cleanHexPrefix(value), 16);
    }
    private static String textResult(JsonNode result, String field) {
        if (!result.isTextual() || result.asText().isBlank()) {
            throw new IllegalStateException(field + " returned an empty result");
        }
        return result.asText();
    }
    private static String abiString(String encoded) {
        if (!encoded.matches("(?i)^0x[0-9a-f]*$")) {
            throw new IllegalStateException("token symbol returned invalid ABI data");
        }
        byte[] bytes = Numeric.hexStringToByteArray(encoded);
        int start;
        int length;
        if (bytes.length == 32) {
            start = 0;
            length = 32;
            while (length > 0 && bytes[length - 1] == 0) length--;
        } else if (bytes.length >= 64) {
            start = new BigInteger(1, Arrays.copyOfRange(bytes, 0, 32)).intValueExact() + 32;
            if (start < 32 || start > bytes.length) {
                throw new IllegalStateException("token symbol returned an invalid ABI offset");
            }
            int lengthOffset = start - 32;
            length = new BigInteger(1,
                    Arrays.copyOfRange(bytes, lengthOffset, lengthOffset + 32)).intValueExact();
            if (length < 1 || start + length > bytes.length) {
                throw new IllegalStateException("token symbol returned an invalid ABI length");
            }
        } else {
            throw new IllegalStateException("token symbol returned incomplete ABI data");
        }
        String symbol = new String(bytes, start, length, StandardCharsets.UTF_8).trim();
        if (symbol.isEmpty()) {
            throw new IllegalStateException("token symbol returned an empty value");
        }
        return symbol;
    }
    private static void validateTokenAmount(BigDecimal amount, String field, int decimals) {
        if (amount == null) return;
        BigDecimal normalized = amount.stripTrailingZeros();
        if (amount.signum() < 0 || normalized.precision() > 78
                || Math.max(normalized.scale(), 0) > Math.min(decimals, 18)) {
            throw new IllegalArgumentException(field
                    + " must be non-negative with at most " + Math.min(decimals, 18) + " fraction digits");
        }
    }
    private void upsertAsset(TokenValues value) {
        chainAssetRepository.upsertNonNative(value.chain(), value.symbol(), value.standard(),
                value.contractAddress(), value.decimals(), value.enabled(), value.minWithdraw(),
                value.minWithdraw());
    }
    private void setAssetActive(String chain, String symbol, boolean active) {
        chainAssetRepository.updateActive(chain, symbol, active);
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
    private ChainValues mergeChain(AccountChainProfile current, ChainCommand command) {
        if (command == null) throw new IllegalArgumentException("chain body is required");
        return new ChainValues(
                upper(or(command.chain(), current == null ? null : current.getChain())),
                lower(or(command.network(), current == null ? null : current.getNetwork())),
                upper(or(command.family(), current == null ? null : current.getFamily())),
                or(command.runtimeCurrencyId(), current == null ? null : current.getRuntimeCurrencyId(), 0),
                or(command.bip44CoinType(), current == null ? null : current.getBip44CoinType(), 0),
                upper(or(command.nativeSymbol(), current == null ? null : current.getNativeSymbol())),
                or(command.explorerUrl(), current == null ? null : current.getExplorerUrl()),
                or(command.depositConfirmations(), current == null ? null : current.getDepositConfirmations(), 1),
                or(command.withdrawConfirmations(), current == null ? null : current.getWithdrawConfirmations(), 1),
                or(command.defaultFeeRate(), current == null ? null : current.getDefaultFee()),
                or(command.dustThreshold(), current == null ? null : current.getDustThreshold()),
                or(command.enabled(), current == null ? null : current.getEnabled(), false),
                or(command.chainId(), current == null ? null : current.getChainId()),
                or(command.gasPolicy(), current == null ? null : current.getGasPolicy()),
                or(command.feeModel(), current == null ? null : current.getFeeModel(),
                        EvmFeeModel.STANDARD.configValue()),
                or(command.scanBatchSize(), current == null ? null : current.getScanBatchSize(), 100),
                or(command.scanEnabled(), current == null ? null : current.getScanEnabled(), false),
                or(command.withdrawEnabled(), current == null ? null : current.getWithdrawEnabled(), false),
                or(command.collectionEnabled(), current == null ? null : current.getCollectionEnabled(), false),
                or(command.transferEnabled(), current == null ? null : current.getTransferEnabled(), false),
                or(command.scanStartHeight(), current == null ? null : current.getScanStartHeight(), 0L),
                or(command.scanMaxBlocksPerRun(), current == null ? null : current.getScanMaxBlocksPerRun(), 0L));
    }
    private ChainRpcNode mergeRpc(ChainRpcNode current, RpcNodeCommand command) {
        if (command == null) throw new IllegalArgumentException("RPC body is required");
        String authType = upper(or(command.authType(), current == null ? "NONE" : current.getAuthType()));
        String connectionType = upper(or(command.connectionType(), current == null ? "HTTP_JSON_RPC" : current.getConnectionType()));
        String apiKey = secret(command.apiKey(), current == null ? null : current.getApiKey());
        String username = secret(command.username(), current == null ? null : current.getUsername());
        String password = secret(command.password(), current == null ? null : current.getPassword());
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
        return ChainRpcNode.builder()
                .environment(lower(or(command.environment(), current == null ? environment : current.getEnvironment())))
                .nodeLabel(or(command.nodeLabel(), current == null ? null : current.getNodeLabel()))
                .purpose(lower(or(command.purpose(), current == null ? "rpc" : current.getPurpose())))
                .connectionType(connectionType).rpcUrl(or(command.rpcUrl(), current == null ? null : current.getRpcUrl()))
                .authType(authType).authHeaderName(or(command.authHeaderName(), current == null ? null : current.getAuthHeaderName()))
                .apiKey(apiKey).username(username).password(password)
                .priority(or(command.priority(), current == null ? null : current.getPriority(), 100))
                .minRequestIntervalMs(or(command.minRequestIntervalMs(), current == null ? null : current.getMinRequestIntervalMs(), 0))
                .enabled(or(command.enabled(), current == null ? null : current.getEnabled(), false))
                .renewalDueAt(or(command.renewalDueAt(), current == null ? null : current.getRenewalDueAt()))
                .remark(or(command.remark(), current == null ? null : current.getRemark())).build();
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
    private ChainView chainView(AccountChainProfile row, List<String> tokens, int rpcCount) {
        return new ChainView(row.getId(), row.getChain(), row.getNetwork(), row.getFamily(),
                row.getRuntimeCurrencyId(), row.getBip44CoinType(), row.getNativeSymbol(), row.getExplorerUrl(),
                row.getDepositConfirmations(), row.getWithdrawConfirmations(), row.getDefaultFee(), row.getDustThreshold(),
                Boolean.TRUE.equals(row.getEnabled()), row.getChainId(), row.getGasPolicy(), row.getFeeModel(),
                intValue(row.getScanBatchSize()), Boolean.TRUE.equals(row.getScanEnabled()),
                Boolean.TRUE.equals(row.getWithdrawEnabled()), Boolean.TRUE.equals(row.getCollectionEnabled()),
                Boolean.TRUE.equals(row.getTransferEnabled()), longValue(row.getScanStartHeight()),
                longValue(row.getScanMaxBlocksPerRun()), List.copyOf(tokens), tokens.size(), rpcCount,
                row.getCreatedAt(), row.getUpdatedAt());
    }

    /** 为链配置补充同一链网络下的代币和 RPC 统计，替代数据库跨表查询。 */

    /** 查询并补充全部链配置的展示字段。 */
    private List<ChainView> chainRows() {
        Map<NetworkKey, List<String>> tokens = new HashMap<>();
        for (var token : tokenConfigRepository.listAll()) {
            tokens.computeIfAbsent(profileKey(token.chain(), token.network()), ignored -> new ArrayList<>())
                    .add(token.symbol());
        }
        tokens.values().forEach(symbols -> symbols.sort(String::compareTo));
        var rpcCounts = rpcNodeRepository.countByNetwork().stream().collect(Collectors.toMap(
                row -> new NetworkKey(row.chain(), row.network()), row -> row.total()));
        return chainProfileRepository.listAll().stream().map(profile -> chainView(profile,
                tokens.getOrDefault(profileKey(profile.getChain(), profile.getNetwork()), List.of()),
                rpcCounts.getOrDefault(new NetworkKey(profile.getChain(), profile.getNetwork()), 0))).toList();
    }

    private RpcNodeView rpcView(ChainRpcNode row) {
        return new RpcNodeView(row.getId(), row.getEnvironment(), row.getNodeLabel(), row.getPurpose(),
                row.getConnectionType(), row.getRpcUrl(), row.getAuthType(), row.getAuthHeaderName(),
                !blank(row.getApiKey()), !blank(row.getUsername()), !blank(row.getPassword()),
                intValue(row.getPriority()), intValue(row.getMinRequestIntervalMs()), Boolean.TRUE.equals(row.getEnabled()),
                row.getRenewalDueAt(), row.getRemark(), row.getLastCheckedAt(), row.getLastLatencyMs(),
                row.getLastHttpStatus(), row.getLastError(), row.getCreatedAt(), row.getUpdatedAt());
    }
    private TokenView tokenView(TokenConfiguration row, GlobalSwitches runtime,
            ChainAsset asset, AccountChainProfile profile) {
        boolean configured = row.enabled();
        boolean assetActive = asset != null && Boolean.TRUE.equals(asset.getActive());
        boolean chainEnabled = profile != null && Boolean.TRUE.equals(profile.getEnabled());
        boolean anyTaskEnabled = profile != null && (
                runtime.taskEnabled(TASK_SCAN, Boolean.TRUE.equals(profile.getScanEnabled()))
                || runtime.taskEnabled(TASK_WITHDRAW, Boolean.TRUE.equals(profile.getWithdrawEnabled()))
                || runtime.taskEnabled(TASK_COLLECTION, Boolean.TRUE.equals(profile.getCollectionEnabled()) && row.collectEnabled())
                || runtime.taskEnabled(TASK_TRANSFER, Boolean.TRUE.equals(profile.getTransferEnabled())));
        List<String> blockers = new ArrayList<>();
        if (!runtime.walletEnabled()) blockers.add("Wallet master switch is off.");
        if (!configured) blockers.add("Token is disabled.");
        if (!assetActive) blockers.add("Chain asset is disabled.");
        if (!chainEnabled) blockers.add("Chain network is disabled.");
        if (chainEnabled && !anyTaskEnabled) blockers.add("No wallet task is effectively enabled for this chain.");
        return new TokenView(row.id(), row.chain(), row.network(), row.symbol(), row.standard(),
                row.contractAddress(), nullable(row.contractAddressBase58()), nullable(row.contractAddressHex()), row.decimals(),
                configured, row.collectEnabled(), row.minDeposit(), row.minWithdraw(), row.collectThreshold(),
                nullable(row.gasStrategy()), row.confirmationRequired(), assetActive, chainEnabled,
                configured && assetActive && runtime.effective(chainEnabled, anyTaskEnabled),
                List.copyOf(blockers), row.createdAt(), row.updatedAt());
    }

    private boolean chainHasRuntimeData(String chain) {
        return chainAddressRepository.existsByChain(chain)
                || ledgerBalanceRepository.existsByChain(chain)
                || depositRecordRepository.existsByChain(chain)
                || withdrawalOrderRepository.existsByChain(chain)
                || collectionRecordRepository.existsByChain(chain);
    }
    private boolean tokenHasRuntimeData(String chain, String symbol) {
        return chainAddressRepository.existsByChainAndAsset(chain, symbol)
                || ledgerBalanceRepository.existsByChainAndAsset(chain, symbol)
                || depositRecordRepository.existsByChainAndAsset(chain, symbol)
                || withdrawalOrderRepository.existsByChainAndAsset(chain, symbol)
                || collectionRecordRepository.existsByChainAndAsset(chain, symbol);
    }
    private RpcTestView saveRpcTest(long nodeId, RpcTestView result) {
        rpcNodeRepository.updateProbe(nodeId, result.checkedAt(), result.latencyMs(),
                result.statusCode(), result.error());
        return result;
    }

    private void audit(CustodyPrincipal actor, String action, String resourceType,
                       String resourceId, String sourceIp, String details) {
        audits.insert(UUID.randomUUID(), null, "PLATFORM_USER", actor.actorId().toString(), action,
                resourceType, resourceId, sourceIp, details);
    }
    private static void requirePlatformAdmin(CustodyPrincipal actor) {
        if (actor == null || !actor.isPlatformAdmin() || actor.tenantId() != null) {
            throw new CustodyForbiddenException("platform administrator access is required");
        }
    }
    private static String rpcAudit(ChainRpcNode value) {
        return "{\"environment\":" + json(value.getEnvironment()) + ",\"purpose\":"
                + json(value.getPurpose()) + ",\"enabled\":" + value.getEnabled()
                + ",\"credentialsConfigured\":"
                + (!blank(value.getApiKey()) || !blank(value.getUsername()) || !blank(value.getPassword())) + "}";
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
    private static void required(String value, String field) {
        if (blank(value)) throw new IllegalArgumentException(field + " is required");
    }
    private static String secret(String requested, String current) {
        return requested == null || requested.isBlank() ? current : requested;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    /** 将数据库值转换为可空字符串，保留 {@code null} 语义。 */
    private static String nullable(Object value) { return value == null ? null : String.valueOf(value); }

    /** 将数据库数值安全转换为金额字段使用的 {@link BigDecimal}。 */

    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String normalizeLower(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String upper(String value) { return value == null ? null : value.trim().toUpperCase(Locale.ROOT); }
    /** 将字符串规范化为小写，供不区分大小写的配置比较使用。 */
    private static String lower(String value) { return value == null ? null : value.trim().toLowerCase(Locale.ROOT); }

    /** 将逗号分隔的配置值拆分为列表，空值返回空列表。 */

    private static int intValue(Object value) { return value == null ? 0 : ((Number) value).intValue(); }

    private static long longValue(Object value) { return value == null ? 0 : ((Number) value).longValue(); }

    private static <T> T or(T requested, T current) { return requested == null ? current : requested; }
    private static int or(Integer requested, Integer current, int fallback) {
        return requested != null ? requested : current != null ? current : fallback;
    }
    private static String or(String requested, String current, String fallback) {
        return requested != null ? requested : current != null ? current : fallback;
    }
    private static long or(Long requested, Long current, long fallback) {
        return requested != null ? requested : current != null ? current : fallback;
    }
    private static boolean or(Boolean requested, Boolean current, boolean fallback) {
        return requested != null ? requested : current != null ? current : fallback;
    }

    public record ChainCommand(String chain, String network, String family, Integer runtimeCurrencyId,
                               Integer bip44CoinType, String nativeSymbol, String explorerUrl,
                               Integer depositConfirmations, Integer withdrawConfirmations,
                               Long defaultFeeRate, Long dustThreshold, Boolean enabled, Long chainId,
                               String gasPolicy, String feeModel, Integer scanBatchSize, Boolean scanEnabled,
                               Boolean withdrawEnabled, Boolean collectionEnabled, Boolean transferEnabled,
                               Long scanStartHeight, Long scanMaxBlocksPerRun) {}
    public record ChainSwitchCommand(Boolean enabled, Boolean scanEnabled, Boolean withdrawEnabled,
                                     Boolean collectionEnabled, Boolean transferEnabled) {}
    public record ChainView(long id, String chain, String network, String family, int runtimeCurrencyId,
                            int bip44CoinType, String nativeSymbol, String explorerUrl,
                            int depositConfirmations, int withdrawConfirmations, Long defaultFeeRate,
                            Long dustThreshold, boolean enabled, Long chainId,
                            String gasPolicy, String feeModel,
                            int scanBatchSize, boolean scanEnabled, boolean withdrawEnabled,
                            boolean collectionEnabled, boolean transferEnabled, long scanStartHeight,
                            long scanMaxBlocksPerRun, List<String> tokenSymbols, int tokenCount, int rpcCount,
                            Instant createdAt, Instant updatedAt) {}
    public record ChainDetailView(ChainView chain, List<RpcNodeView> rpcNodes, List<TokenView> tokens,
                                  List<String> checks, boolean production, String environment) {}
    private record ChainValues(String chain, String network, String family, int runtimeCurrencyId,
                               int bip44CoinType, String nativeSymbol, String explorerUrl,
                               int depositConfirmations, int withdrawConfirmations, Long defaultFeeRate,
                               Long dustThreshold, boolean enabled, Long chainId,
                               String gasPolicy, String feeModel,
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

    private static NetworkKey profileKey(String chain, String network) {
        return new NetworkKey(normalize(chain), normalize(network));
    }

    private record NetworkKey(String chain, String network) { }
    private record AssetKey(String chain, String symbol) { }
}
