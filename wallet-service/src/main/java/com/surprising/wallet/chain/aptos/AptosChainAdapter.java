package com.surprising.wallet.chain.aptos;

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
 * Aptos 链适配器，实现 {@link BlockchainAdapter} 接口。
 *
 * <p>提供 APT 原生币和同质化资产（Fungible Asset）的转账报价与充值扫描能力。
 * 底层依赖 {@link AptosDepositScanner} 进行链上充值事件扫描。</p>
 *
 * <p>支持的链能力：{@link Capability#NATIVE_QUOTE} 和 {@link Capability#TOKEN_QUOTE}。</p>
 */
@Component
public class AptosChainAdapter implements BlockchainAdapter {

    /** 链标识常量 */
    private static final String CHAIN = "APTOS";

    /** 充值扫描器 */
    private final AptosDepositScanner scanner;

    /** 数据库访问仓库 */
    private final ChainJdbcRepository repository;

    /**
     * 构造器注入。
     *
     * @param scanner   {@link AptosDepositScanner} 充值扫描器
     * @param repository {@link ChainJdbcRepository} 数据库仓库
     */
    public AptosChainAdapter(AptosDepositScanner scanner, ChainJdbcRepository repository) {
        this.scanner = scanner;
        this.repository = repository;
    }

    @Override
    public ChainType chainType() {
        return ChainType.APTOS;
    }

    @Override
    public java.util.Set<Capability> capabilities() {
        return java.util.Set.of(Capability.NATIVE_QUOTE, Capability.TOKEN_QUOTE);
    }

    @Override
    public String family() {
        return "aptos";
    }

    @Override
    public String describe() {
        return "Aptos Ed25519, account sequence transaction, APT and Fungible Asset wallet engine.";
    }

    /**
     * 为原生 APT 转账提供报价。
     *
     * <p>费用从 {@link AccountChainProfile#getDefaultFee()} 获取。</p>
     *
     * @param request 转账请求
     * @return APT 转账报价
     */
    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        AccountChainProfile profile = profile();
        return new TransferQuote(ChainType.APTOS, "APT", request.fromAddress(), request.toAddress(),
                request.amount(), BigDecimal.valueOf(profile.getDefaultFee()), null, null, null, null,
                "aptos_account::transfer", true, "APT native transfer");
    }

    /**
     * 为 Aptos 同质化资产（FA）代币转账提供报价。
     *
     * <p>会校验代币的 fungible asset metadata 是否存在。</p>
     *
     * @param request 转账请求
     * @return 代币转账报价
     * @throws IllegalArgumentException 如果代币未配置
     */
    @Override
    public TransferQuote quoteTokenTransfer(TransferRequest request) {
        TokenDefinition token = repository.findToken(CHAIN, request.assetSymbol())
                .orElseThrow(() -> new IllegalArgumentException("Aptos token not configured: "
                        + request.assetSymbol()));
        AptosFungibleAsset.requireMetadata(token);
        return new TransferQuote(ChainType.APTOS, token.getSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), BigDecimal.valueOf(profile().getDefaultFee()), null, null, null, null,
                "primary_fungible_store::transfer", true, "Aptos FA token transfer");
    }

    /**
     * 扫描并记录充值事件。
     *
     * @param height 当前扫描高度（暂未使用）
     * @return 充值事件列表
     */
    @Override
    public List<DepositEvent> scanDeposits(long height) {
        return scanner.scanAndCredit();
    }

    /**
     * 获取当前链的配置档案。
     *
     * @return {@link AccountChainProfile} 链配置
     * @throws IllegalStateException 如果链配置未启用
     */
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
}
