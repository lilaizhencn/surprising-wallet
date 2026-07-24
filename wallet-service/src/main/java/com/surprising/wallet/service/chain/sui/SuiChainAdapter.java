package com.surprising.wallet.service.chain.sui;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.service.chain.BlockchainAdapter;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
public
class SuiChainAdapter implements BlockchainAdapter {
    private static final String CHAIN = "SUI";
    private final SuiDepositScanner scanner;
    private final ChainJdbcRepository repository;

    @Override
    public ChainType chainType() {
        return ChainType.SUI;
    }

    @Override
    public java.util.Set<Capability> capabilities() {
        return java.util.Set.of(Capability.NATIVE_QUOTE, Capability.TOKEN_QUOTE);
    }

    @Override
    public String family() {
        return "sui";
    }

    @Override
    public String describe() {
        return "Sui Ed25519 object/coin transaction, SUI and Coin<T> wallet engine.";
    }

    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        AccountChainProfile profile = profile();
        BigDecimal fee = BigDecimal.valueOf(profile.getDefaultFee());
        return new TransferQuote(ChainType.SUI, "SUI", request.fromAddress(), request.toAddress(),
                request.amount(), fee, null, null, null, null,
                "sui_paySui", true, "Sui native paySui transfer");
    }

    @Override
    public TransferQuote quoteTokenTransfer(TransferRequest request) {
        TokenDefinition token = repository.findToken(CHAIN, request.assetSymbol())
                .orElseThrow(() -> new IllegalArgumentException("unsupported Sui token " + request.assetSymbol()));
        BigDecimal fee = BigDecimal.valueOf(profile().getDefaultFee());
        return new TransferQuote(ChainType.SUI, token.getSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), fee, null, null, null, null,
                "sui_pay", true, "Sui Coin<T> transfer");
    }

    @Override
    public List<DepositEvent> scanDeposits(long height) {
        return scanner.scanAndCredit();
    }
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
}
