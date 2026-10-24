package com.surprising.wallet.service;

import com.surprising.wallet.devfaucet.DevFaucetAmountGenerator;
import com.surprising.wallet.devfaucet.DevFaucetFunding;
import com.surprising.wallet.devfaucet.DevFaucetProperties;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodyDepositRepository;
import com.surprising.wallet.repository.CustodyGasAccountRepository;
import com.surprising.wallet.repository.CustodyTenantChainTableRepository;
import com.surprising.wallet.repository.CustodyTenantTableRepository;
import com.surprising.wallet.repository.DevFaucetFundingRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.chain.model.TokenConfiguration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.sql.Timestamp;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 开发环境水龙头业务服务，负责候选发现、补币发送、重试、对账和审计。
 */
@Service
@ConditionalOnProperty(prefix = "sw.wallet.dev-faucet", name = "enabled", havingValue = "true")
@Slf4j
public class DevFaucetService {
    private final DevFaucetProperties properties;
    private final DevFaucetFundingRepository fundings;
    private final CustodyAddressRepository addresses;
    private final CustodyTenantTableRepository tenants;
    private final CustodyTenantChainTableRepository tenantChains;
    private final CustodyGasAccountRepository gasAccounts;
    private final ChainProfileRepository profiles;
    private final ChainAssetRepository assets;
    private final TokenConfigRepository tokens;
    private final CustodyDepositRepository deposits;
    private final DevFaucetRpcClient rpcClient;
    private final CustodyAuditRepository audits;
    private final ObjectMapper objectMapper;
    private final DevFaucetAmountGenerator amounts;
    private final String environment;

    public DevFaucetService(DevFaucetProperties properties, DevFaucetFundingRepository fundings,
            CustodyAddressRepository addresses, CustodyTenantTableRepository tenants,
            CustodyTenantChainTableRepository tenantChains, CustodyGasAccountRepository gasAccounts,
            ChainProfileRepository profiles, ChainAssetRepository assets, TokenConfigRepository tokens,
            CustodyDepositRepository deposits, DevFaucetRpcClient rpcClient, CustodyAuditRepository audits,
            ObjectMapper objectMapper, DevFaucetAmountGenerator amounts,
            @Value("${sw.app.env.name:dev}") String environment) {
        this.properties = properties;
        this.fundings = fundings;
        this.addresses = addresses;
        this.tenants = tenants;
        this.tenantChains = tenantChains;
        this.gasAccounts = gasAccounts;
        this.profiles = profiles;
        this.assets = assets;
        this.tokens = tokens;
        this.deposits = deposits;
        this.rpcClient = rpcClient;
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.amounts = amounts;
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        properties.validate(environment);
    }

    public void runOnce() {
        Duration staleAge = properties.getRequestTimeout().multipliedBy(2).plusSeconds(5);
        int stale = fundings.recoverStaleSending(staleAge);
        int confirmed = reconcileConfirmed();
        if (stale > 0 || confirmed > 0) {
            log.info("dev faucet reconciled confirmed={} staleUnknown={}", confirmed, stale);
        }

        for (Candidate candidate : discover(properties.getBatchSize())) {
            fundings.create(candidate.tenantId(), candidate.custodyAddressId(), candidate.chain(),
                    candidate.network(), candidate.assetSymbol(), candidate.purpose(), candidate.address(),
                    candidate.contractAddress(), candidate.decimals(), amountFor(candidate));
        }
        for (DevFaucetFunding funding : fundings.due(properties.getBatchSize(), properties.getMaxAttempts())) {
            fund(funding);
        }
    }

    private List<Candidate> discover(int limit) {
        if (limit <= 0) return List.of();
        var activeTenants = tenants.listActiveIds();
        if (activeTenants.isEmpty()) return List.of();
        Set<TenantChain> activeChains = tenantChains.listActiveByTenants(activeTenants).stream()
                .map(row -> new TenantChain(row.tenantId(), row.chain())).collect(Collectors.toSet());
        Set<UUID> gasAddresses = new HashSet<>(gasAccounts.listActiveAddressIds());
        Set<ChainNetwork> scanning = profiles.listProfiles(true).stream()
                .filter(profile -> Boolean.TRUE.equals(profile.getScanEnabled()))
                .map(profile -> new ChainNetwork(profile.getChain(), profile.getNetwork())).collect(Collectors.toSet());
        Map<String, List<ChainAsset>> nativeAssets = new HashMap<>();
        Map<AssetKey, ChainAsset> tokenAssets = new HashMap<>();
        for (var asset : assets.listActiveDefinitions()) {
            if (Boolean.TRUE.equals(asset.getNativeAsset())) {
                nativeAssets.computeIfAbsent(asset.getChain().toUpperCase(Locale.ROOT),
                        ignored -> new ArrayList<>()).add(asset);
            } else {
                tokenAssets.put(new AssetKey(asset.getChain(), asset.getSymbol()), asset);
            }
        }
        Map<ChainNetwork, List<TokenConfiguration>> customerTokens = new HashMap<>();
        for (var token : tokens.listAll()) {
            var asset = tokenAssets.get(new AssetKey(token.chain(), token.symbol()));
            if (token.enabled() && "ETH".equalsIgnoreCase(token.chain())
                    && Set.of("USDT", "USDC").contains(token.symbol()) && asset != null
                    && asset.getContractAddress() != null
                    && asset.getContractAddress().equalsIgnoreCase(token.contractAddress())) {
                customerTokens.computeIfAbsent(new ChainNetwork(token.chain(), token.network()),
                        ignored -> new ArrayList<>()).add(token);
            }
        }
        var candidates = new ArrayList<Candidate>();
        for (var address : addresses.listActiveForTenantsAndChains(activeTenants, List.of("BTC", "ETH"))) {
            String chain = address.chain();
            if (!activeChains.contains(new TenantChain(address.tenantId(), chain))
                    || !scanning.contains(new ChainNetwork(chain, address.network()))) continue;
            boolean gas = gasAddresses.contains(address.id());
            if (!gas && !"API".equalsIgnoreCase(address.source())) continue;
            for (var asset : nativeAssets.getOrDefault(chain, List.of())) {
                candidates.add(new Candidate(address.tenantId(), address.id(), chain, address.network(),
                        asset.getSymbol(), gas ? "TENANT_GAS" : "CUSTOMER_DEPOSIT", address.address(),
                        null, asset.getDecimals()));
            }
            if (!gas && "ETH".equals(chain)) {
                for (var token : customerTokens.getOrDefault(new ChainNetwork(chain, address.network()), List.of())) {
                    candidates.add(new Candidate(address.tenantId(), address.id(), chain, address.network(),
                            token.symbol(), "CUSTOMER_DEPOSIT", address.address(), token.contractAddress(), token.decimals()));
                }
            }
        }
        var existing = new HashSet<>(fundings.existingForAddresses(
                candidates.stream().map(Candidate::custodyAddressId).distinct().toList()));
        return candidates.stream()
                .filter(candidate -> !existing.contains(new DevFaucetFundingRepository.FundingKey(
                        candidate.custodyAddressId(), candidate.assetSymbol(), candidate.purpose())))
                .sorted(Comparator.comparing(Candidate::custodyAddressId).thenComparing(Candidate::assetSymbol))
                .limit(limit).toList();
    }

    private int reconcileConfirmed() {
        int updated = 0;
        for (var funding : fundings.sent()) {
            Timestamp creditedAt = deposits.findConfirmedAt(funding.tenantId(), funding.custodyAddressId(),
                    funding.chain(), funding.assetSymbol(), funding.txHash());
            if (creditedAt != null) updated += fundings.markConfirmed(funding.id(), creditedAt);
        }
        return updated;
    }

    private BigDecimal amountFor(Candidate candidate) {
        if ("TENANT_GAS".equals(candidate.purpose())) {
            return switch (candidate.chain()) {
                case "BTC" -> properties.getBitcoin().getGasAmount();
                case "ETH" -> properties.getEvm().getGasAmount();
                default -> throw new IllegalArgumentException(
                        "unsupported dev faucet gas chain " + candidate.chain());
            };
        }
        return switch (candidate.assetSymbol()) {
            case "BTC" -> amounts.next(properties.getBitcoin().getCustomer());
            case "ETH" -> amounts.next(properties.getEvm().getCustomer());
            case "USDT" -> amounts.next(properties.getEvm().getUsdt());
            case "USDC" -> amounts.next(properties.getEvm().getUsdc());
            default -> throw new IllegalArgumentException(
                    "unsupported dev faucet asset " + candidate.assetSymbol());
        };
    }

    private void fund(DevFaucetFunding funding) {
        if (!fundings.markSending(funding.id())) {
            return;
        }
        try {
            String transactionHash = rpcClient.send(funding);
            fundings.markSent(funding.id(), transactionHash);
            audit(funding, "DEV_FAUCET.SENT", transactionHash, null);
        } catch (DevFaucetRpcClient.RejectedException error) {
            fundings.markFailed(funding.id(), error.getMessage(), properties.getRetryDelay());
            audit(funding, "DEV_FAUCET.FAILED", null, error.getMessage());
        } catch (DevFaucetRpcClient.AmbiguousException error) {
            fundings.markUnknown(funding.id(), error.getMessage());
            audit(funding, "DEV_FAUCET.UNKNOWN", null, error.getMessage());
        }
    }

    private void audit(DevFaucetFunding funding, String action, String transactionHash, String error) {
        try {
            String details = objectMapper.writeValueAsString(Map.of(
                    "chain", funding.chain(),
                    "network", funding.network(),
                    "assetSymbol", funding.assetSymbol(),
                    "purpose", funding.purpose(),
                    "amount", funding.requestedAmount().toPlainString(),
                    "txHash", transactionHash == null ? "" : transactionHash,
                    "error", error == null ? "" : error));
            audits.insert(UUID.randomUUID(),
                    funding.tenantId(), "SYSTEM", "dev-faucet", action,
                    "DEV_FAUCET_FUNDING", funding.id().toString(), null, details);
        } catch (RuntimeException auditError) {
            log.error("failed to write dev faucet audit for {}", funding.id(), auditError);
        }
    }

    private record Candidate(UUID tenantId, UUID custodyAddressId, String chain, String network,
            String assetSymbol, String purpose, String address, String contractAddress, int decimals) { }

    private record TenantChain(UUID tenant, String chain) {
        private TenantChain { chain = chain.toUpperCase(Locale.ROOT); }
    }

    private record ChainNetwork(String chain, String network) {
        private ChainNetwork {
            chain = chain.toUpperCase(Locale.ROOT);
            network = network.toLowerCase(Locale.ROOT);
        }
    }

    private record AssetKey(String chain, String symbol) {
        private AssetKey {
            chain = chain.toUpperCase(Locale.ROOT);
            symbol = symbol.toUpperCase(Locale.ROOT);
        }
    }
}
