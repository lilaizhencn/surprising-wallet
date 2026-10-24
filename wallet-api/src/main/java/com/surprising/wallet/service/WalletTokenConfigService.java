package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.TokenConfiguration;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import tools.jackson.databind.JsonNode;
import com.surprising.wallet.config.WalletRpcPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import org.web3j.utils.Numeric;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainRpcNodeRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.service.WalletRuntimeConfigService.GlobalSwitches;
import static com.surprising.wallet.service.WalletRuntimeConfigService.*;
import static com.surprising.wallet.service.WalletConfigSupport.*;

/** 链上验证在事务外完成；配置、资产和审计在复核版本后的短事务内更新。 */
@Service
public class WalletTokenConfigService {
    private final WalletRuntimeConfigService runtimeConfig;
    private final ChainProfileRepository chainProfileRepository;
    private final ChainRpcNodeRepository rpcNodeRepository;
    private final ChainAssetRepository chainAssetRepository;
    private final TokenConfigRepository tokenConfigRepository;
    private final WalletConfigIdentityGuard identityGuard;
    private final CustodyAuditRepository audits;
    private final ObjectMapper mapper;
    private final String environment;
    private final WalletRpcClient rpcClient;
    private final TransactionTemplate transactions;

    public WalletTokenConfigService(WalletRuntimeConfigService runtimeConfig,
                                   ChainProfileRepository chainProfileRepository,
                                   ChainRpcNodeRepository rpcNodeRepository,
                                   ChainAssetRepository chainAssetRepository,
                                   TokenConfigRepository tokenConfigRepository,
                                   WalletConfigIdentityGuard identityGuard,
                                   CustodyAuditRepository audits, ObjectMapper mapper,
                                   @Value("${sw.app.env.name:dev}") String environment,
                                   WalletRpcClient rpcClient, PlatformTransactionManager manager) {
        this.runtimeConfig = runtimeConfig;
        this.chainProfileRepository = chainProfileRepository;
        this.rpcNodeRepository = rpcNodeRepository;
        this.chainAssetRepository = chainAssetRepository;
        this.tokenConfigRepository = tokenConfigRepository;
        this.identityGuard = identityGuard;
        this.audits = audits;
        this.mapper = mapper;
        this.environment = normalizeLower(environment);
        this.rpcClient = rpcClient;
        this.transactions = new TransactionTemplate(manager);
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

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TokenView createToken(CustodyPrincipal actor, TokenCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        TokenValues values = mergeToken(null, command);
        AccountChainProfile profile = validateToken(values);
        return transactions.execute(status -> {
            requireUnchangedProfile(profile);
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
            return tokenView(id);
        });
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TokenView updateToken(CustodyPrincipal actor, long id,
                                 TokenCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        TokenConfiguration current = requireToken(id);
        TokenValues values = mergeToken(current, command);
        AccountChainProfile profile = validateToken(values);
        return transactions.execute(status -> {
            requireUnchangedProfile(profile);
            requireUnchangedToken(current);
            if (!current.chain().equalsIgnoreCase(values.chain())
                    || !current.network().equalsIgnoreCase(values.network())
                    || !current.symbol().equalsIgnoreCase(values.symbol())
                    || !current.contractAddress().equalsIgnoreCase(values.contractAddress())
                    || current.decimals() != values.decimals()) {
                identityGuard.requireUnusedToken(current.chain(), current.symbol());
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
                chainAssetRepository.updateActive(current.chain(), current.symbol(), false);
            }
            audit(actor, "WALLET_TOKEN.UPDATE", "TOKEN_CONFIG", String.valueOf(id), sourceIp,
                    tokenAudit(values));
            return tokenView(id);
        });
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TokenView updateTokenStatus(CustodyPrincipal actor, long id,
                                       TokenStatusCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        if (command == null || command.enabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        TokenConfiguration current = requireToken(id);
        boolean collectEnabled = command.enabled() && current.collectEnabled();
        TokenValues values = new TokenValues(current.chain(), current.network(), current.symbol(),
                current.standard(), current.contractAddress(), current.contractAddressBase58(),
                current.contractAddressHex(), current.decimals(), command.enabled(), collectEnabled,
                current.minDeposit(), current.minWithdraw(), current.collectThreshold(),
                current.gasStrategy(), current.confirmationRequired());
        AccountChainProfile profile = values.enabled() ? validateToken(values) : null;
        return transactions.execute(status -> {
            if (profile != null) requireUnchangedProfile(profile);
            requireUnchangedToken(current);
            tokenConfigRepository.updateEnabled(id, values.enabled(), values.collectEnabled());
            if (values.enabled()) {
                upsertAsset(values);
            } else {
                chainAssetRepository.updateActive(current.chain(), current.symbol(), false);
            }
            audit(actor, "WALLET_TOKEN.STATUS_UPDATE", "TOKEN_CONFIG", String.valueOf(id), sourceIp,
                    Map.of("enabled", command.enabled()));
            return tokenView(id);
        });
    }

    private void requireUnchangedProfile(AccountChainProfile observed) {
        AccountChainProfile current = chainProfileRepository.lockById(observed.getId())
                .orElseThrow(() -> new IllegalStateException("chain configuration changed during token validation"));
        if (!observed.equals(current)) {
            throw new IllegalStateException("chain configuration changed during token validation; retry the request");
        }
    }

    private void requireUnchangedToken(TokenConfiguration observed) {
        if (!Objects.equals(observed, tokenConfigRepository.lockById(observed.id()).orElse(null))) {
            throw new IllegalStateException("token configuration changed during validation; retry the request");
        }
    }

    private TokenConfiguration requireToken(long id) {
        return tokenConfigRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("token not found"));
    }

    private TokenView tokenView(long id) {
        TokenConfiguration row = requireToken(id);
        return tokenView(row, runtimeConfig.globalSwitches(),
                chainAssetRepository.find(row.chain(), row.symbol()).orElse(null),
                chainProfileRepository.findByChainAndNetwork(row.chain(), row.network()).orElse(null));
    }

    private AccountChainProfile validateToken(TokenValues value) {
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
        return profile;
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
            BigInteger observedChainId = quantity(rpcClient.jsonRpc(rpc, "eth_chainId", List.of()), "chain id");
            if (!observedChainId.equals(BigInteger.valueOf(profile.getChainId()))) {
                throw new IllegalStateException("token validation RPC chain id does not match chain profile");
            }
        }
        String code = textResult(rpcClient.jsonRpc(rpc, "eth_getCode", List.of(address, "latest")), "contract code");
        if (!code.matches("(?i)^0x[0-9a-f]+$") || code.matches("(?i)^0x0*$")) {
            throw new IllegalArgumentException("contractAddress has no deployed bytecode on the configured network");
        }
        int onChainDecimals = quantity(rpcClient.jsonRpc(rpc, "eth_call", List.of(Map.of("to", address, "data", "0x313ce567"), "latest")),
                "token decimals").intValueExact();
        if (onChainDecimals != value.decimals()) {
            throw new IllegalArgumentException("configured decimals do not match the token contract");
        }
        String onChainSymbol = abiString(textResult(rpcClient.jsonRpc(rpc, "eth_call", List.of(Map.of("to", address, "data", "0x95d89b41"), "latest")),
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
        String symbol;
        try {
            if (Numeric.cleanHexPrefix(encoded).length() == 64) {
                byte[] bytes = ((Bytes32) FunctionReturnDecoder.decode(encoded,
                        org.web3j.abi.Utils.convert(List.of(new TypeReference<Bytes32>() { }))).getFirst()).getValue();
                int length = bytes.length;
                while (length > 0 && bytes[length - 1] == 0) length--;
                symbol = new String(bytes, 0, length, StandardCharsets.UTF_8).trim();
            } else {
                var decoded = FunctionReturnDecoder.decode(encoded,
                        org.web3j.abi.Utils.convert(List.of(new TypeReference<Utf8String>() { })));
                symbol = ((Utf8String) decoded.getFirst()).getValue().trim();
            }
        } catch (RuntimeException error) {
            throw new IllegalStateException("token symbol returned invalid ABI data", error);
        }
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

    private TokenValues mergeToken(TokenConfiguration current, TokenCommand command) {
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
                row.contractAddress(), row.contractAddressBase58(), row.contractAddressHex(), row.decimals(),
                configured, row.collectEnabled(), row.minDeposit(), row.minWithdraw(), row.collectThreshold(),
                row.gasStrategy(), row.confirmationRequired(), assetActive, chainEnabled,
                configured && assetActive && runtime.effective(chainEnabled, anyTaskEnabled),
                List.copyOf(blockers), row.createdAt(), row.updatedAt());
    }

    private void audit(CustodyPrincipal actor, String action, String resourceType,
                       String resourceId, String sourceIp, Map<String, ?> details) {
        audits.insert(UUID.randomUUID(), null, "PLATFORM_USER", actor.actorId().toString(), action,
                resourceType, resourceId, sourceIp, mapper.writeValueAsString(details));
    }

    private static Map<String, Object> tokenAudit(TokenValues value) {
        return Map.of("chain", value.chain(), "network", value.network(),
                "symbol", value.symbol(), "enabled", value.enabled());
    }

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

    List<TokenView> listForProfile(AccountChainProfile profile) {
        var configured = tokenConfigRepository.listByChainAndNetwork(profile.getChain(), profile.getNetwork());
        var assets = chainAssetRepository.listDefinitionsByChain(profile.getChain()).stream().collect(Collectors.toMap(
                row -> new AssetKey(row.getChain(), row.getSymbol()), row -> row));
        GlobalSwitches runtime = runtimeConfig.globalSwitches();
        return configured.stream().sorted(java.util.Comparator.comparing(row -> normalize(row.symbol())))
                .map(row -> tokenView(row, runtime, assets.get(new AssetKey(row.chain(), row.symbol())), profile)).toList();
    }

}
