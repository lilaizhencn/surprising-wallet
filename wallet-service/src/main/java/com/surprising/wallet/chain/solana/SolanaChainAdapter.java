package com.surprising.wallet.chain.solana;

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
 * Solana 链适配器，实现 {@link BlockchainAdapter} 接口。
 *
 * <p>基于 Solana 的 Ed25519 签名方案和基于 blockhash 的交易模型，支持：
 * <ul>
 *   <li>原生 SOL 转账（SystemProgram.transfer）</li>
 *   <li>SPL Token 转账（TokenProgram.transferChecked，通过 ATA 账户）</li>
 *   <li>通过 {@link SolanaDepositScanner} 扫描签名和解析指令完成充值检测</li>
 * </ul>
 *
 * <p>手续费以 lamports 为单位（1 SOL = 1,000,000,000 lamports），
 * 每笔交易需使用最新的 recent blockhash。
 *
 * @see SolanaDepositScanner
 * @see SolanaTransactionService
 * @see SolanaRpcClient
 */
@Component
public
class SolanaChainAdapter implements BlockchainAdapter {

    /** Solana 链标识常量 */
    private static final String CHAIN = "SOLANA";

    /** 充值扫描器 */
    private final SolanaDepositScanner scanner;

    /** 链配置数据库访问 */
    private final ChainJdbcRepository repository;

    /**
     * @param scanner    充值扫描器
     * @param repository 链配置数据库访问
     */
    public SolanaChainAdapter(SolanaDepositScanner scanner, ChainJdbcRepository repository) {
        this.scanner = scanner;
        this.repository = repository;
    }

    @Override
    public ChainType chainType() {
        return ChainType.SOLANA;
    }

    @Override
    public java.util.Set<Capability> capabilities() {
        return java.util.Set.of(Capability.NATIVE_QUOTE, Capability.TOKEN_QUOTE);
    }

    @Override
    public String family() {
        return "solana";
    }

    @Override
    public String describe() {
        return "Solana Ed25519, blockhash transaction, native SOL and SPL/ATA wallet engine.";
    }

    /**
     * 估算 SOL 原生转账手续费。
     *
     * @param request 转账请求
     * @return 包含手续费估算的报价
     */
    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        AccountChainProfile profile = profile();
        return new TransferQuote(ChainType.SOLANA, request.assetSymbol(), request.fromAddress(),
                request.toAddress(), request.amount(), BigDecimal.valueOf(profile.getDefaultFee()),
                null, null, null, null, "system-program-transfer", true, "SOL native transfer");
    }

    /**
     * 估算 SPL Token 转账手续费。
     *
     * @param request 转账请求
     * @return 包含手续费和 SPL 信息的报价
     * @throws IllegalArgumentException 如果代币未配置
     */
    @Override
    public TransferQuote quoteTokenTransfer(TransferRequest request) {
        TokenDefinition token = repository.findToken(CHAIN, request.assetSymbol())
                .orElseThrow(() -> new IllegalArgumentException("SPL token not configured: " + request.assetSymbol()));
        return new TransferQuote(ChainType.SOLANA, token.getSymbol(), request.fromAddress(),
                request.toAddress(), request.amount(), BigDecimal.valueOf(profile().getDefaultFee()),
                null, null, null, null, "spl-token-transfer-checked", true,
                "SPL transfer via associated token accounts");
    }

    /**
     * 扫描 Solana 链上的充值事件（委托 {@link SolanaDepositScanner#scanAndCredit()}）。
     *
     * @param height 当前高度（Solana 使用 slot，此参数在实现中不使用）
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
