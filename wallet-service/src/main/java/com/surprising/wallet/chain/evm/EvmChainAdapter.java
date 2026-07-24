package com.surprising.wallet.chain.evm;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.chain.BlockchainAdapter;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * EVM 兼容链（ETH、BNB、POLYGON、ARBITRUM 等）统一适配器。
 *
 * <p>一条适配器覆盖所有 EVM 兼容链，通过 {@link ChainType} 区分具体链。
 * 每条链有独立的 {@link ChainProfile}（RPC URL、chainId、原生币符号等），
 * 但共享相同的交易构建、Gas 估算、日志扫描和充值扫描逻辑。
 *
 * <p>实现的能力：地址生成、地址校验、充值扫描、确认策略、粉尘策略、
 * 最佳高度、区块交易扫描、余额刷新、签名交易广播。
 */
@Component
public
class EvmChainAdapter implements BlockchainAdapter {

    private final EvmNonceManager nonceManager;
    private final TokenRegistry tokenRegistry;
    private final EvmGasEstimator gasEstimator;
    private final EvmTransactionBuilder transactionBuilder;
    private final EvmLogScanner logScanner;
    private final EvmDepositScanner depositScanner;
    /** 链配置缓存，key 为 ChainType */
    private final Map<ChainType, ChainProfile> profiles = new EnumMap<>(ChainType.class);
    private final ChainJdbcRepository repository;

    @Autowired
    public EvmChainAdapter(EvmNonceManager nonceManager, TokenRegistry tokenRegistry,
                           EvmGasEstimator gasEstimator, EvmTransactionBuilder transactionBuilder,
                           EvmLogScanner logScanner, EvmDepositScanner depositScanner,
                           ChainJdbcRepository repository) {
        this.nonceManager = nonceManager;
        this.tokenRegistry = tokenRegistry;
        this.gasEstimator = gasEstimator;
        this.transactionBuilder = transactionBuilder;
        this.logScanner = logScanner;
        this.depositScanner = depositScanner;
        this.repository = repository;
        registerDbProfiles();
    }

    public EvmChainAdapter(EvmNonceManager nonceManager, TokenRegistry tokenRegistry,
                           EvmGasEstimator gasEstimator, EvmTransactionBuilder transactionBuilder,
                           EvmLogScanner logScanner) {
        this.nonceManager = nonceManager;
        this.tokenRegistry = tokenRegistry;
        this.gasEstimator = gasEstimator;
        this.transactionBuilder = transactionBuilder;
        this.logScanner = logScanner;
        this.depositScanner = null;
        this.repository = null;
        registerProfiles();
    }

    @Override
    public ChainType chainType() {
        return ChainType.ETH;
    }

    @Override
    public java.util.Set<Capability> capabilities() {
        return depositScanner == null
                ? java.util.Set.of(Capability.NATIVE_QUOTE, Capability.TOKEN_QUOTE)
                : java.util.Set.of(
                        Capability.NATIVE_QUOTE, Capability.TOKEN_QUOTE,
                        Capability.DEPOSIT_SCAN);
    }

    @Override
    public boolean supports(ChainType chainType) {
        return chainType != null && chainType.isEvm();
    }

    @Override
    public String family() {
        return "evm";
    }

    @Override
    public String describe() {
        return "Unified EVM engine with nonce manager, gas estimator, transaction builder, ERC20 processor and log scanner.";
    }

    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        ChainProfile profile = profileOf(request.chainType());
        long nonce = nonceManager.reserve(request.chainType(), request.fromAddress(), 0L);
        EvmGasEstimator.GasQuote gasQuote = gasEstimator.quote(profile, request, false);
        long maxFeePerGasWei = gasQuote.gasPriceGwei() * 1_000_000_000L;
        return new TransferQuote(request.chainType(), request.assetSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), gasQuote.feeEth(), nonce, gasQuote.gasLimit(), maxFeePerGasWei,
                0L, transactionBuilder.buildNativePayload(), true, "native evm transfer");
    }

    @Override
    public TransferQuote quoteTokenTransfer(TransferRequest request) {
        ChainProfile profile = profileOf(request.chainType());
        TokenDefinition tokenDefinition = tokenRegistry.find(request.chainType().name(), request.assetSymbol())
                .orElseThrow(() -> new IllegalArgumentException("token not registered: " + request.assetSymbol()));
        long nonce = nonceManager.reserve(request.chainType(), request.fromAddress(), 0L);
        EvmGasEstimator.GasQuote gasQuote = gasEstimator.quote(profile, request, true);
        String payload = transactionBuilder.buildErc20TransferPayload(request.toAddress(), request.amount(), tokenDefinition);
        long maxFeePerGasWei = gasQuote.gasPriceGwei() * 1_000_000_000L;
        return new TransferQuote(request.chainType(), request.assetSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), gasQuote.feeEth(), nonce, gasQuote.gasLimit(), maxFeePerGasWei, 0L,
                payload, true, "erc20 transfer");
    }

    @Override
    public List<DepositEvent> scanDeposits(long height) {
        if (depositScanner == null) {
            throw new IllegalStateException("EVM deposit scanner runtime is not configured");
        }
        try {
            return depositScanner.scanAndCreditNativeEth(height);
        } catch (IOException e) {
            throw new IllegalStateException("EVM deposit scan failed at height " + height, e);
        }
    }
    public ChainProfile getProfile(ChainType chainType) {
        return profileOf(chainType);
    }
    private void registerProfiles() {
        registerProfile(ChainType.ETH, "ETH", 11155111L, 1L);
        // The current runtime is testnet-only. Keep the adapter chainIds aligned
        // with application.yaml so signed EVM payloads never mix mainnet IDs with
        // testnet RPC endpoints during fork and integration tests.
        registerProfile(ChainType.BNB, "BNB", 97L, 1L);
        registerProfile(ChainType.POLYGON, "POL", 80002L, 1L);
        registerProfile(ChainType.ARBITRUM, "ETH_ARB", 421614L, 1L);
        registerProfile(ChainType.OPTIMISM, "ETH_OP", 11155420L, 1L);
        registerProfile(ChainType.BASE, "ETH_BASE", 84532L, 1L);
        registerProfile(ChainType.AVAX_C, "AVAX_C", 43113L, 1L);
        registerProfile(ChainType.HYPEREVM, "HYPE", 998L, 1L);
        registerProfile(ChainType.MANTLE, "MNT", 5003L, 1L);
        registerProfile(ChainType.LINEA, "ETH_LINEA", 59141L, 1L);
        registerProfile(ChainType.SCROLL, "ETH_SCROLL", 534351L, 1L);
        registerProfile(ChainType.UNICHAIN, "ETH_UNICHAIN", 1301L, 1L);
    }
    private void registerDbProfiles() {
        for (AccountChainProfile profile : repository.listEnabledChainProfiles()) {
            if (!"evm".equalsIgnoreCase(profile.getFamily())) {
                continue;
            }
            ChainType chainType = ChainType.valueOf(profile.getChain());
            Long chainId = profile.getChainId();
            if (chainId == null || chainId <= 0) {
                throw new IllegalStateException("missing chain_profile.chain_id for " + profile.getChain());
            }
            profiles.put(chainType, ChainProfile.builder()
                    .chainType(chainType)
                    .nativeSymbol(profile.getNativeSymbol())
                    .depositConfirmations(profile.getDepositConfirmations())
                    .withdrawConfirmations(profile.getWithdrawConfirmations())
                    .defaultGasLimit(BigDecimal.valueOf(21_000L))
                    .gasPriceFloor(BigDecimal.valueOf(
                            profile.getDefaultFee() == null || profile.getDefaultFee() <= 0 ? 1L : profile.getDefaultFee()))
                    .chainId(chainId)
                    .build());
        }
    }
    private void registerProfile(ChainType chainType, String nativeSymbol, Long chainId, Long gasFloorGwei) {
        profiles.put(chainType, ChainProfile.builder()
                .chainType(chainType)
                .nativeSymbol(nativeSymbol)
                .depositConfirmations(1)
                .withdrawConfirmations(1)
                .defaultGasLimit(BigDecimal.valueOf(21_000L))
                .gasPriceFloor(BigDecimal.valueOf(gasFloorGwei))
                .chainId(chainId)
                .build());
    }
    private ChainProfile profileOf(ChainType chainType) {
        ChainProfile profile = profiles.get(chainType);
        if (profile == null) {
            throw new IllegalArgumentException("Unsupported EVM chain: " + chainType);
        }
        return profile;
    }
}
