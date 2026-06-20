package com.surprising.wallet.service.chain.evm;

import com.surprising.wallet.common.chain.ChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.service.chain.BlockchainAdapter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class EvmChainAdapter implements BlockchainAdapter {
    private final EvmNonceManager nonceManager;
    private final TokenRegistry tokenRegistry;
    private final EvmGasEstimator gasEstimator;
    private final EvmTransactionBuilder transactionBuilder;
    private final EvmLogScanner logScanner;
    private final Map<ChainType, ChainProfile> profiles = new EnumMap<>(ChainType.class);

    public EvmChainAdapter(EvmNonceManager nonceManager, TokenRegistry tokenRegistry,
                           EvmGasEstimator gasEstimator, EvmTransactionBuilder transactionBuilder,
                           EvmLogScanner logScanner) {
        this.nonceManager = nonceManager;
        this.tokenRegistry = tokenRegistry;
        this.gasEstimator = gasEstimator;
        this.transactionBuilder = transactionBuilder;
        this.logScanner = logScanner;
        registerProfiles();
    }

    @Override
    public ChainType chainType() {
        return ChainType.ETH;
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
        return List.of();
    }

    public ChainProfile getProfile(ChainType chainType) {
        return profileOf(chainType);
    }

    private void registerProfiles() {
        registerProfile(ChainType.ETH, "ETH", 1L, 1L);
        registerProfile(ChainType.BNB, "BNB", 56L, 1L);
        registerProfile(ChainType.POLYGON, "MATIC", 137L, 1L);
        registerProfile(ChainType.ARBITRUM, "ETH_ARB", 42161L, 1L);
        registerProfile(ChainType.OPTIMISM, "ETH_OP", 10L, 1L);
        registerProfile(ChainType.BASE, "ETH_BASE", 8453L, 1L);
        registerProfile(ChainType.AVAX_C, "AVAX_C", 43114L, 1L);
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
