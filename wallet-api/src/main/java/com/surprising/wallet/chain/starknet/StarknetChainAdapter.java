package com.surprising.wallet.chain.starknet;

import com.surprising.wallet.chain.BlockchainAdapter;
import com.surprising.wallet.chain.model.TransferQuote;
import com.surprising.wallet.chain.model.TransferRequest;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.repository.ChainJdbcRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Starknet 适配器，提供 STRK、ERC-20 报价和 Transfer 充值扫描能力。
 */
@Component
public class StarknetChainAdapter implements BlockchainAdapter {
    /** 链标识。 */
    private static final String CHAIN = "STARKNET";
    /** Starknet 手续费精度。 */
    private static final int STRK_DECIMALS = 18;

    /** Starknet 充值扫描器。 */
    private final StarknetDepositScanner scanner;
    /** 链配置仓储。 */
    private final ChainJdbcRepository repository;

    /** 构造 Starknet 链适配器。 */
    public StarknetChainAdapter(StarknetDepositScanner scanner, ChainJdbcRepository repository) {
        this.scanner = scanner;
        this.repository = repository;
    }

    /** 返回 Starknet 链类型。 */
    @Override
    public ChainType chainType() {
        return ChainType.STARKNET;
    }

    /** 返回适配器能力。 */
    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.NATIVE_QUOTE, Capability.TOKEN_QUOTE, Capability.DEPOSIT_SCAN);
    }

    /** 返回 Starknet 链族。 */
    @Override
    public String family() {
        return "starknet";
    }

    /** 返回适配器描述。 */
    @Override
    public String describe() {
        return "Starknet native account abstraction with STRK and ERC-20 Transfer scanning.";
    }

    /** 计算 STRK 原生转账报价。 */
    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        AccountChainProfile profile = profile();
        return new TransferQuote(ChainType.STARKNET, profile.getNativeSymbol(), request.fromAddress(),
                request.toAddress(), request.amount(), fee(profile), null, null, null, null,
                "account.execute_v3", true, "Starknet account contract transfer");
    }

    /** 计算 Starknet ERC-20 转账报价。 */
    @Override
    public TransferQuote quoteTokenTransfer(TransferRequest request) {
        TokenDefinition token = repository.findToken(CHAIN, request.assetSymbol())
                .orElseThrow(() -> new IllegalArgumentException("Starknet token not configured: "
                        + request.assetSymbol()));
        return new TransferQuote(ChainType.STARKNET, token.getSymbol(), request.fromAddress(),
                request.toAddress(), request.amount(), fee(profile()), null, null, null, null,
                "account.execute_v3 -> transfer", true, "Starknet ERC-20 transfer");
    }

    /** 扫描指定高度范围的充值事件。 */
    @Override
    public List<DepositEvent> scanDeposits(long height) {
        return scanner.scanAndCredit(profile());
    }

    /** 查询当前启用的 Starknet 链配置。 */
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }

    /** 返回 STRK 展示单位的手续费估算。 */
    private BigDecimal fee(AccountChainProfile profile) {
        return profile.getDefaultFee() == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(profile.getDefaultFee()).movePointLeft(STRK_DECIMALS);
    }
}
