package com.surprising.wallet.chain.xrp;

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
 * XRP Ledger (XRPL) 链适配器，实现 {@link BlockchainAdapter} 接口。
 *
 * <p>基于 XRPL 的 Payment 交易类型，支持：
 * <ul>
 *   <li>原生 XRP 转账（直接 Payment）</li>
 *   <li>发行货币（Issued Currency）转账（需接收方建立 TrustLine）</li>
 *   <li>通过 {@link XrpDepositScanner} 扫描链上充值</li>
 * </ul>
 *
 * <p>XRP 使用 secp256k1 经典地址方案（r 开头），每次交易需要 AccountSequence 递增。
 * 手续费以 drops 为单位（1 XRP = 1,000,000 drops）。
 *
 * @see XrpDepositScanner
 * @see XrpTransactionService
 * @see XrpRpcClient
 */
@Component
public
class XrpChainAdapter implements BlockchainAdapter {

    /** XRP 链标识常量 */
    private static final String CHAIN = "XRP";

    /** 充值扫描器 */
    private final XrpDepositScanner scanner;

    /** 链配置数据库访问 */
    private final ChainJdbcRepository repository;

    /**
     * @param scanner    充值扫描器
     * @param repository 链配置数据库访问
     */
    public XrpChainAdapter(XrpDepositScanner scanner, ChainJdbcRepository repository) {
        this.scanner = scanner;
        this.repository = repository;
    }

    @Override
    public ChainType chainType() {
        return ChainType.XRP;
    }

    @Override
    public java.util.Set<Capability> capabilities() {
        return java.util.Set.of(Capability.NATIVE_QUOTE, Capability.TOKEN_QUOTE);
    }

    @Override
    public String family() {
        return "xrp";
    }

    @Override
    public String describe() {
        return "XRP Ledger secp256k1 classic address wallet with XRP and issued-currency Payment support.";
    }

    /**
     * 估算 XRP 原生转账手续费。
     *
     * <p>手续费从链配置的 defaultFee 读取（单位 drops），未配置时默认 12 drops（0.000012 XRP）。
     *
     * @param request 转账请求
     * @return 包含手续费估算的报价
     */
    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        AccountChainProfile profile = profile();
        BigDecimal fee = BigDecimal.valueOf(profile.getDefaultFee() == null ? 12L : profile.getDefaultFee())
                .movePointLeft(6);
        return new TransferQuote(ChainType.XRP, request.assetSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), fee, null, null, null, null, "xrpl-payment", true,
                "XRP native Payment");
    }

    /**
     * 估算 XRP 发行货币（Issued Currency）转账手续费。
     *
     * <p>接收方必须预先建立 TrustLine，手续费同原生 XRP 转账。
     *
     * @param request 转账请求
     * @return 包含手续费和发行货币信息的报价
     * @throws IllegalArgumentException 如果代币未配置
     */
    @Override
    public TransferQuote quoteTokenTransfer(TransferRequest request) {
        TokenDefinition token = repository.findToken(CHAIN, request.assetSymbol())
                .orElseThrow(() -> new IllegalArgumentException(
                        "XRPL issued currency not configured: " + request.assetSymbol()));
        BigDecimal fee = BigDecimal.valueOf(profile().getDefaultFee() == null ? 12L : profile().getDefaultFee())
                .movePointLeft(6);
        return new TransferQuote(ChainType.XRP, token.getSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), fee, null, null, null, null, "xrpl-issued-currency-payment",
                true, "XRPL issued currency Payment; receiver must have a trustline");
    }

    /**
     * 扫描 XRP 链上的充值事件（委托 {@link XrpDepositScanner#scanAndCredit()}）。
     *
     * @param height 当前高度（XRPL 使用 ledger_index，此参数在 XRP 中不使用）
     * @return 充值事件列表
     */
    @Override
    public List<DepositEvent> scanDeposits(long height) {
        return scanner.scanAndCredit();
    }
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
}
