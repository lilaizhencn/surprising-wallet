package com.surprising.wallet.service.chain.xrp;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.service.chain.BlockchainAdapter;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class XrpChainAdapter implements BlockchainAdapter {
    private static final String CHAIN = "XRP";

    private final XrpDepositScanner scanner;
    private final ChainJdbcRepository repository;

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

    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        AccountChainProfile profile = profile();
        BigDecimal fee = BigDecimal.valueOf(profile.getDefaultFee() == null ? 12L : profile.getDefaultFee())
                .movePointLeft(6);
        return new TransferQuote(ChainType.XRP, request.assetSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), fee, null, null, null, null, "xrpl-payment", true,
                "XRP native Payment");
    }

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

    @Override
    public List<DepositEvent> scanDeposits(long height) {
        return scanner.scanAndCredit();
    }

    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
}
