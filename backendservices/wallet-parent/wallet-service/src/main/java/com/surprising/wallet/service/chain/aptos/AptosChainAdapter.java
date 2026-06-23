package com.surprising.wallet.service.chain.aptos;

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
public class AptosChainAdapter implements BlockchainAdapter {
    private final AptosDepositScanner scanner;
    private final ChainJdbcRepository repository;

    @Value("${atomex.aptos.network:devnet}")
    private String network = "devnet";

    public AptosChainAdapter(AptosDepositScanner scanner, ChainJdbcRepository repository) {
        this.scanner = scanner;
        this.repository = repository;
    }

    @Override
    public ChainType chainType() {
        return ChainType.APTOS;
    }

    @Override
    public String family() {
        return "aptos";
    }

    @Override
    public String describe() {
        return "Aptos Ed25519, account sequence transaction, APT and Coin<T> wallet engine.";
    }

    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        AccountChainProfile profile = profile();
        return new TransferQuote(ChainType.APTOS, "APT", request.fromAddress(), request.toAddress(),
                request.amount(), BigDecimal.valueOf(profile.getDefaultFee()), null, null, null, null,
                "aptos_account::transfer", true, "APT native transfer");
    }

    @Override
    public TransferQuote quoteTokenTransfer(TransferRequest request) {
        TokenDefinition token = repository.findToken("APTOS", request.assetSymbol())
                .orElseThrow(() -> new IllegalArgumentException("Aptos token not configured: "
                        + request.assetSymbol()));
        return new TransferQuote(ChainType.APTOS, token.getSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), BigDecimal.valueOf(profile().getDefaultFee()), null, null, null, null,
                "aptos_account::transfer_coins", true, "Aptos Coin<T> token transfer");
    }

    @Override
    public List<DepositEvent> scanDeposits(long height) {
        return scanner.scanAndCredit();
    }

    private AccountChainProfile profile() {
        return repository.findAccountChainProfile("APTOS", network)
                .orElseThrow(() -> new IllegalStateException("missing enabled APTOS/" + network + " profile"));
    }
}
