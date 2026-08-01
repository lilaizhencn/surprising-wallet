package com.surprising.wallet.chain.sui;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.chain.model.TransferQuote;
import com.surprising.wallet.chain.model.TransferRequest;
import com.surprising.wallet.chain.BlockchainAdapter;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sui 链适配器，实现 {@link BlockchainAdapter} 接口。
 *
 * <p>提供 SUI 原生币和 Coin&lt;T&gt; 代币的转账报价与充值扫描能力。
 * Sui 使用 Ed25519 签名，交易通过 PTB（Programmable Transaction Block）构造。</p>
 *
 * <p>支持的链能力：{@link Capability#NATIVE_QUOTE} 和 {@link Capability#TOKEN_QUOTE}。</p>
 */
@Component
@RequiredArgsConstructor
public
class SuiChainAdapter implements BlockchainAdapter {

    /** 链标识常量 */
    private static final String CHAIN = "SUI";

    /** 充值扫描器 */
    private final SuiDepositScanner scanner;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /**
     * 获取或查询 {@code chainType} 对应的数据，并向调用方返回当前业务状态。
     */
    @Override
    public ChainType chainType() {
        return ChainType.SUI;
    }

    /**
     * 获取或查询 {@code capabilities} 对应的数据，并向调用方返回当前业务状态。
     */
    @Override
    public java.util.Set<Capability> capabilities() {
        return java.util.Set.of(Capability.NATIVE_QUOTE, Capability.TOKEN_QUOTE);
    }

    /**
     * 获取或查询 {@code family} 对应的数据，并向调用方返回当前业务状态。
     */
    @Override
    public String family() {
        return "sui";
    }

    /**
     * 获取或查询 {@code describe} 对应的数据，并向调用方返回当前业务状态。
     */
    @Override
    public String describe() {
        return "Sui Ed25519 object/coin transaction, SUI and Coin<T> wallet engine.";
    }

    /**
     * 为 SUI 原生转账提供报价。
     */
    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        AccountChainProfile profile = profile();
        BigDecimal fee = BigDecimal.valueOf(profile.getDefaultFee());
        return new TransferQuote(ChainType.SUI, "SUI", request.fromAddress(), request.toAddress(),
                request.amount(), fee, null, null, null, null,
                "sui_paySui", true, "Sui native paySui transfer");
    }

    /**
     * 为 Sui Coin&lt;T&gt; 代币转账提供报价。
     */
    @Override
    public TransferQuote quoteTokenTransfer(TransferRequest request) {
        TokenDefinition token = repository.findToken(CHAIN, request.assetSymbol())
                .orElseThrow(() -> new IllegalArgumentException("unsupported Sui token " + request.assetSymbol()));
        BigDecimal fee = BigDecimal.valueOf(profile().getDefaultFee());
        return new TransferQuote(ChainType.SUI, token.getSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), fee, null, null, null, null,
                "sui_pay", true, "Sui Coin<T> transfer");
    }

    /**
     * 扫描并记录充值事件。
     */
    @Override
    public List<DepositEvent> scanDeposits(long height) {
        return scanner.scanAndCredit();
    }

    /**
     * 获取当前链的配置档案。
     */
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
}
