package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.config.WalletEnvironmentPolicy;
import com.surprising.wallet.config.WalletRpcPolicy;
import com.surprising.wallet.common.chain.EvmFeeModel;
import com.surprising.wallet.common.chain.EvmGasPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;

import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainRpcNodeRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.service.WalletRpcConfigService.RpcNodeView;
import com.surprising.wallet.service.WalletTokenConfigService.TokenView;
import static com.surprising.wallet.service.WalletConfigSupport.*;

/** 链配置写入、启用约束和链详情组合。 */
@Service
public class WalletChainConfigService {
    private final ChainProfileRepository chainProfileRepository;
    private final ChainRpcNodeRepository rpcNodeRepository;
    private final ChainAssetRepository chainAssetRepository;
    private final TokenConfigRepository tokenConfigRepository;
    private final WalletConfigIdentityGuard identityGuard;
    private final CustodyAuditRepository audits;
    private final ObjectMapper mapper;
    private final String environment;
    private final WalletRpcConfigService rpcConfig;
    private final WalletTokenConfigService tokenConfig;

    public WalletChainConfigService(ChainProfileRepository chainProfileRepository,
                                   ChainRpcNodeRepository rpcNodeRepository,
                                   ChainAssetRepository chainAssetRepository,
                                   TokenConfigRepository tokenConfigRepository,
                                   WalletConfigIdentityGuard identityGuard,
                                   CustodyAuditRepository audits, ObjectMapper mapper,
                                   @Value("${sw.app.env.name:dev}") String environment,
                                   WalletRpcConfigService rpcConfig,
                                   WalletTokenConfigService tokenConfig) {
        this.chainProfileRepository = chainProfileRepository;
        this.rpcNodeRepository = rpcNodeRepository;
        this.chainAssetRepository = chainAssetRepository;
        this.tokenConfigRepository = tokenConfigRepository;
        this.identityGuard = identityGuard;
        this.audits = audits;
        this.mapper = mapper;
        this.environment = normalizeLower(environment);
        this.rpcConfig = rpcConfig;
        this.tokenConfig = tokenConfig;
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
                        .map(WalletConfigSupport::normalize).anyMatch(value -> value.contains(tokenFilter)))
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
        List<RpcNodeView> rpcNodes = rpcConfig.listForProfile(profile);
        List<TokenView> tokens = tokenConfig.listForProfile(profile);
        ChainView chain = chainView(profile, tokens.stream().map(TokenView::symbol).sorted().toList(), rpcNodes.size());
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
                Map.of("chain", values.chain(), "network", values.network()));
        return getChain(actor, id);
    }

    @Transactional
    public ChainDetailView updateChain(CustodyPrincipal actor, long id,
                                       ChainCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        AccountChainProfile current = requireChain(id);
        chainProfileRepository.lockChain(current.getChain());
        ChainValues values = mergeChain(current, command);
        validateChainValues(values);
        if (!current.getChain().equalsIgnoreCase(values.chain())
                || !current.getNetwork().equalsIgnoreCase(values.network())
                || current.getRuntimeCurrencyId() != values.runtimeCurrencyId()
                || current.getBip44CoinType() != values.bip44CoinType()
                || !java.util.Objects.equals(current.getChainId(),values.chainId())
                || !java.util.Objects.equals(upper(current.getNativeSymbol()), upper(values.nativeSymbol()))
                || !java.util.Objects.equals(upper(current.getFamily()), upper(values.family()))
                || !java.util.Objects.equals(upper(current.getGasPolicy()), upper(values.gasPolicy()))
                || !java.util.Objects.equals(upper(current.getFeeModel()), upper(values.feeModel()))) {
            identityGuard.requireUnusedChain(current.getChain());
        }
        if (Boolean.TRUE.equals(current.getEnabled()) && !values.enabled()) identityGuard.requireUnusedChain(current.getChain());
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
                Map.of("chain", values.chain(), "network", values.network()));
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
        chainProfileRepository.lockChain(current.getChain());
        if (Boolean.TRUE.equals(current.getEnabled()) && !command.enabled()) identityGuard.requireUnusedChain(current.getChain());
        if (command.enabled()) {
            validateCanEnable(current.getChain(), current.getNetwork(), id);
        }
        chainProfileRepository.updateSwitches(id, command.enabled(), command.scanEnabled(),
                command.withdrawEnabled(), command.collectionEnabled(), command.transferEnabled());
        audit(actor, "WALLET_CHAIN.SWITCHES_UPDATE", "CHAIN_PROFILE", String.valueOf(id), sourceIp,
                Map.of("enabled", command.enabled()));
        return getChain(actor, id);
    }

    private AccountChainProfile requireChain(long id) {
        return chainProfileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("chain profile not found"));
    }

    private void validateCanEnable(String chain, String network, Long currentId) {
        if (WalletEnvironmentPolicy.isProduction(environment)
                && !WalletEnvironmentPolicy.isProductionNetwork(network)) {
            throw new IllegalStateException("production environment can enable only a production network");
        }
        chainProfileRepository.lockChain(chain);
        identityGuard.requireNetwork(chain,network);
        if (chainProfileRepository.hasOtherEnabledNetwork(chain, currentId)) {
            throw new IllegalStateException("only one network can be enabled per chain at a time");
        }
        boolean hasTokens = tokenConfigRepository.countByChain(chain) > 0;
        rpcConfig.requireEnabledNodes(chain, network, hasTokens);
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

    private void audit(CustodyPrincipal actor, String action, String resourceType,
                       String resourceId, String sourceIp, Map<String, ?> details) {
        audits.insert(UUID.randomUUID(), null, "PLATFORM_USER", actor.actorId().toString(), action,
                resourceType, resourceId, sourceIp, mapper.writeValueAsString(details));
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

    private static NetworkKey profileKey(String chain, String network) {
        return new NetworkKey(normalize(chain), normalize(network));
    }

    private record NetworkKey(String chain, String network) { }

}
