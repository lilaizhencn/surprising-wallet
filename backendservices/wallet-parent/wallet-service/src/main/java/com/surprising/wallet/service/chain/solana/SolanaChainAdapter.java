package com.surprising.wallet.service.chain.solana;

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
public class SolanaChainAdapter implements BlockchainAdapter {
    private final SolanaDepositScanner scanner;
    private final ChainJdbcRepository repository;

    @Value("${atomex.solana.network:devnet}")
    private String network;

    public SolanaChainAdapter(SolanaDepositScanner scanner, ChainJdbcRepository repository) {
        this.scanner = scanner;
        this.repository = repository;
    }

    @Override
    public ChainType chainType() {
        return ChainType.SOLANA;
    }

    @Override
    public String family() {
        return "solana";
    }

    @Override
    public String describe() {
        return "Solana Ed25519, blockhash transaction, native SOL and SPL/ATA wallet engine.";
    }

    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        AccountChainProfile profile = profile();
        return new TransferQuote(ChainType.SOLANA, request.assetSymbol(), request.fromAddress(),
                request.toAddress(), request.amount(), BigDecimal.valueOf(profile.getDefaultFee()),
                null, null, null, null, "system-program-transfer", true, "SOL native transfer");
    }

    @Override
    public TransferQuote quoteTokenTransfer(TransferRequest request) {
        TokenDefinition token = repository.findToken("SOLANA", request.assetSymbol())
                .orElseThrow(() -> new IllegalArgumentException("SPL token not configured: " + request.assetSymbol()));
        return new TransferQuote(ChainType.SOLANA, token.getSymbol(), request.fromAddress(),
                request.toAddress(), request.amount(), BigDecimal.valueOf(profile().getDefaultFee()),
                null, null, null, null, "spl-token-transfer-checked", true,
                "SPL transfer via associated token accounts");
    }

    @Override
    public List<DepositEvent> scanDeposits(long height) {
        return scanner.scanAndCredit();
    }

    private AccountChainProfile profile() {
        return repository.findAccountChainProfile("SOLANA", network)
                .orElseThrow(() -> new IllegalStateException("missing enabled SOLANA/" + network + " profile"));
    }
}
