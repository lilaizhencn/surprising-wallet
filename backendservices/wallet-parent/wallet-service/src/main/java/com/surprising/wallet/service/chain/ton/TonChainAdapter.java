package com.surprising.wallet.service.chain.ton;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.service.chain.BlockchainAdapter;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class TonChainAdapter implements BlockchainAdapter {
    private final TonDepositScanner scanner;
    private final ChainJdbcRepository repository;

    @Value("${atomex.ton.network:testnet}")
    private String network = "testnet";

    public TonChainAdapter(TonDepositScanner scanner, ChainJdbcRepository repository) {
        this.scanner = scanner;
        this.repository = repository;
    }

    @Override
    public ChainType chainType() {
        return ChainType.TON;
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
        TokenDefinition token = repository.findToken("TON", request.assetSymbol())
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

    private AccountChainProfile profile() {
        return repository.findAccountChainProfile("TON", network)
                .orElseThrow(() -> new IllegalStateException("missing enabled TON/" + network + " profile"));
    }
}
