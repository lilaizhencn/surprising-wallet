package com.surprising.wallet.chain.ton;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.chain.BlockchainAdapter;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * TON 链适配器，实现 {@link BlockchainAdapter} 接口，委托给 TON 链各服务。
 *
 * <p>提供 TON 原生币和 TEP-74 Jetton 代币的报价能力。
 * 充值扫描委托给 {@link TonDepositScanner}。</p>
 *
 * <p>TON 使用 WalletV4R2 智能合约，消息格式为 Cell/BOC，
 * Jetton 转账通过 TEP-74 标准的 {@code jetton_transfer} 消息实现。</p>
 *
 * @see TonDepositScanner
 */
@Component
public
class TonChainAdapter implements BlockchainAdapter {

    /** 链标识 */
    private static final String CHAIN = "TON";

    /** TON 充值扫描器 */
    private final TonDepositScanner scanner;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    public TonChainAdapter(TonDepositScanner scanner, ChainJdbcRepository repository) {
        this.scanner = scanner;
        this.repository = repository;
    }

    @Override
    public ChainType chainType() {
        return ChainType.TON;
    }

    @Override
    public java.util.Set<Capability> capabilities() {
        return java.util.Set.of(Capability.NATIVE_QUOTE, Capability.TOKEN_QUOTE);
    }

    @Override
    public String family() {
        return "ton";
    }

    @Override
    public String describe() {
        return "TON WalletV4R2 message engine with seqno, comments, Cell/BOC and TEP-74 Jettons.";
    }

    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        return new TransferQuote(ChainType.TON, request.assetSymbol(), request.fromAddress(),
                request.toAddress(), request.amount(), BigDecimal.valueOf(profile().getDefaultFee()),
                null, null, null, null, "wallet-v4r2-internal-message", true,
                "TON native internal message");
    }

    @Override
    public TransferQuote quoteTokenTransfer(TransferRequest request) {
        TokenDefinition token = repository.findToken(CHAIN, request.assetSymbol())
                .orElseThrow(() -> new IllegalArgumentException("Jetton not configured: " + request.assetSymbol()));
        return new TransferQuote(ChainType.TON, token.getSymbol(), request.fromAddress(),
                request.toAddress(), request.amount(), BigDecimal.valueOf(70_000_000L),
                null, null, null, null, "tep-74-jetton-transfer", true,
                "Jetton wallet transfer message");
    }

    @Override
    public List<DepositEvent> scanDeposits(long height) {
        return scanner.scanAndCredit();
    }

    /** 获取 TON 链配置 */
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
}
