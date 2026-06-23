package com.surprising.wallet.service.chain.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.SolanaTransactionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.core.Transaction;
import org.p2p.solanaj.programs.AssociatedTokenProgram;
import org.p2p.solanaj.programs.SystemProgram;
import org.p2p.solanaj.programs.TokenProgram;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SolanaTransactionService {
    private static final String CHAIN = "SOLANA";

    private final SolanaRpcClient rpc;
    private final SolanaKeyService keyService;
    private final SolanaAddressService addressService;
    private final ChainJdbcRepository repository;

    @Value("${atomex.solana.network:devnet}")
    private String network = "devnet";

    public String sendNative(long derivationIndex, String toAddress, long lamports) {
        if (lamports <= 0) {
            throw new IllegalArgumentException("lamports must be positive");
        }
        Account sender = keyService.account(derivationIndex);
        Transaction transaction = new Transaction()
                .addInstruction(SystemProgram.transfer(
                        sender.getPublicKey(), new PublicKey(toAddress), lamports));
        return signAndSend(transaction, List.of(sender));
    }

    public String sendToken(long derivationIndex, String mintAddress, String toOwnerAddress,
                            long atomicAmount, int decimals) {
        if (atomicAmount <= 0) {
            throw new IllegalArgumentException("token amount must be positive");
        }
        Account sender = keyService.account(derivationIndex);
        PublicKey mint = new PublicKey(mintAddress);
        PublicKey sourceAta = new PublicKey(addressService.associatedTokenAddress(
                sender.getPublicKeyBase58(), mintAddress));
        PublicKey destinationOwner = new PublicKey(toOwnerAddress);
        PublicKey destinationAta = new PublicKey(addressService.associatedTokenAddress(
                toOwnerAddress, mintAddress));

        Transaction transaction = new Transaction();
        if (rpc.getAccountInfo(destinationAta.toBase58()).isNull()) {
            transaction.addInstruction(AssociatedTokenProgram.createIdempotent(
                    sender.getPublicKey(), destinationOwner, mint));
        }
        transaction.addInstruction(TokenProgram.transferChecked(
                sourceAta, destinationAta, atomicAmount, (byte) decimals, sender.getPublicKey(), mint));
        return signAndSend(transaction, List.of(sender));
    }

    public String withdrawNative(String orderNo, long userId, ChainAddressRecord from,
                                 String toAddress, BigDecimal amountLamports) {
        Optional<String> previous = repository.findWithdrawalTxHash(CHAIN, orderNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        long amount = amountLamports.longValueExact();
        long fee = profile().getDefaultFee();
        int inserted = repository.createWithdrawalOrder(orderNo, userId, CHAIN, "SOL", toAddress,
                amountLamports, BigDecimal.valueOf(fee));
        if (inserted == 0) {
            return repository.findWithdrawalTxHash(CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("withdrawal already claimed without tx hash"));
        }
        BigDecimal totalDebit = amountLamports.add(BigDecimal.valueOf(fee));
        if (!repository.freezeLedgerBalance(CHAIN, "SOL", from.getAccountId(), totalDebit)) {
            repository.updateWithdrawalStatus(CHAIN, orderNo, "FAILED", from.getAddress(), null,
                    "insufficient available ledger balance");
            throw new IllegalStateException("insufficient SOL ledger balance");
        }
        repository.updateWithdrawalStatus(CHAIN, orderNo, "FROZEN", from.getAddress(), null, null);
        try {
            repository.updateWithdrawalStatus(CHAIN, orderNo, "SIGNING", from.getAddress(), null, null);
            String signature = sendNative(from.getAddressIndex(), toAddress, amount);
            repository.updateWithdrawalStatus(CHAIN, orderNo, "SENT", from.getAddress(), signature, null);
            recordTransaction(signature, from.getAddress(), toAddress, "SOL", null, amountLamports, fee, "SENT");
            return signature;
        } catch (RuntimeException e) {
            repository.releaseLockedBalance(CHAIN, "SOL", from.getAccountId(), totalDebit);
            repository.updateWithdrawalStatus(CHAIN, orderNo, "FAILED", from.getAddress(), null, e.getMessage());
            throw e;
        }
    }

    public String withdrawToken(String orderNo, long userId, ChainAddressRecord from,
                                String mintAddress, String toOwnerAddress, BigDecimal atomicAmount) {
        Optional<String> previous = repository.findWithdrawalTxHash(CHAIN, orderNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, mintAddress)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Solana mint " + mintAddress));
        int inserted = repository.createWithdrawalOrder(orderNo, userId, CHAIN, token.getSymbol(), toOwnerAddress,
                atomicAmount, BigDecimal.ZERO);
        if (inserted == 0) {
            return repository.findWithdrawalTxHash(CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("withdrawal already claimed without tx hash"));
        }
        if (!repository.freezeLedgerBalance(CHAIN, token.getSymbol(), from.getAccountId(), atomicAmount)) {
            repository.updateWithdrawalStatus(CHAIN, orderNo, "FAILED", from.getOwnerAddress(), null,
                    "insufficient available token ledger balance");
            throw new IllegalStateException("insufficient " + token.getSymbol() + " ledger balance");
        }
        repository.updateWithdrawalStatus(CHAIN, orderNo, "FROZEN", from.getOwnerAddress(), null, null);
        try {
            repository.updateWithdrawalStatus(CHAIN, orderNo, "SIGNING", from.getOwnerAddress(), null, null);
            String signature = sendToken(from.getAddressIndex(), mintAddress, toOwnerAddress,
                    atomicAmount.longValueExact(), token.getDecimals());
            repository.updateWithdrawalStatus(CHAIN, orderNo, "SENT", from.getOwnerAddress(), signature, null);
            recordTransaction(signature, from.getAddress(), toOwnerAddress, token.getSymbol(), mintAddress,
                    atomicAmount, profile().getDefaultFee(), "SENT");
            return signature;
        } catch (RuntimeException e) {
            repository.releaseLockedBalance(CHAIN, token.getSymbol(), from.getAccountId(), atomicAmount);
            repository.updateWithdrawalStatus(CHAIN, orderNo, "FAILED", from.getOwnerAddress(), null, e.getMessage());
            throw e;
        }
    }

    public String collectNative(String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigDecimal amountLamports) {
        Optional<String> previous = repository.findCollectionTxHash(CHAIN, collectionNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        long fee = profile().getDefaultFee();
        repository.createCollectionRecord(collectionNo, CHAIN, "SOL", from.getAddress(), hotAddress,
                amountLamports, BigDecimal.valueOf(fee), null);
        if (repository.claimCollectionSigning(CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("collection is not retryable"));
        }
        try {
            String signature = sendNative(from.getAddressIndex(), hotAddress, amountLamports.longValueExact());
            repository.updateCollectionStatus(CHAIN, collectionNo, "SENT", signature, null, null);
            recordTransaction(signature, from.getAddress(), hotAddress, "SOL", null,
                    amountLamports, fee, "SENT");
            return signature;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public String collectToken(String collectionNo, ChainAddressRecord from, String mintAddress,
                               String hotOwnerAddress, BigDecimal atomicAmount) {
        Optional<String> previous = repository.findCollectionTxHash(CHAIN, collectionNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, mintAddress)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Solana mint " + mintAddress));
        repository.createCollectionRecord(collectionNo, CHAIN, token.getSymbol(), from.getAddress(),
                hotOwnerAddress, atomicAmount, BigDecimal.valueOf(profile().getDefaultFee()), null);
        if (repository.claimCollectionSigning(CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("collection is not retryable"));
        }
        try {
            String signature = sendToken(from.getAddressIndex(), mintAddress, hotOwnerAddress,
                    atomicAmount.longValueExact(), token.getDecimals());
            repository.updateCollectionStatus(CHAIN, collectionNo, "SENT", signature, null, null);
            recordTransaction(signature, from.getAddress(), hotOwnerAddress, token.getSymbol(), mintAddress,
                    atomicAmount, profile().getDefaultFee(), "SENT");
            return signature;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public boolean confirmWithdrawal(String orderNo, String assetSymbol, String accountId, BigDecimal debitAmount) {
        String signature = repository.findWithdrawalTxHash(CHAIN, orderNo).orElseThrow();
        JsonNode status = requireSuccessfulConfirmation(signature, Duration.ofMinutes(2));
        if (repository.markWithdrawalConfirmed(CHAIN, orderNo, signature) == 1) {
            if (!repository.settleLockedDebit(CHAIN, assetSymbol, accountId, debitAmount)) {
                throw new IllegalStateException("unable to settle locked " + assetSymbol + " balance");
            }
            updateConfirmedTransaction(signature, status);
            return true;
        }
        return false;
    }

    public boolean confirmCollection(String collectionNo) {
        String signature = repository.findCollectionTxHash(CHAIN, collectionNo).orElseThrow();
        JsonNode status = requireSuccessfulConfirmation(signature, Duration.ofMinutes(2));
        boolean updated = repository.markCollectionConfirmed(CHAIN, collectionNo, signature) == 1;
        updateConfirmedTransaction(signature, status);
        return updated;
    }

    public JsonNode requireSuccessfulConfirmation(String signature, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            JsonNode status = rpc.getSignatureStatus(signature);
            if (status != null && !status.isNull()) {
                if (!status.path("err").isNull() && !status.path("err").isMissingNode()) {
                    throw new IllegalStateException("Solana transaction failed: " + status.path("err"));
                }
                String confirmation = status.path("confirmationStatus").asText();
                if ("confirmed".equals(confirmation) || "finalized".equals(confirmation)) {
                    return status;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("confirmation wait interrupted", e);
            }
        }
        throw new IllegalStateException("Solana confirmation timeout for " + signature);
    }

    private String signAndSend(Transaction transaction, List<Account> signers) {
        transaction.setRecentBlockHash(rpc.getLatestBlockhash());
        transaction.sign(signers);
        return rpc.sendTransaction(transaction.serialize());
    }

    private AccountChainProfile profile() {
        return repository.findAccountChainProfile(CHAIN, network)
                .orElseThrow(() -> new IllegalStateException("missing enabled SOLANA/" + network + " profile"));
    }

    private void recordTransaction(String signature, String from, String to, String symbol, String mint,
                                   BigDecimal amount, long fee, String status) {
        repository.recordSolanaTransaction(SolanaTransactionRecord.builder()
                .chain(CHAIN)
                .signature(signature)
                .fromAddress(from)
                .toAddress(to)
                .assetSymbol(symbol)
                .mintAddress(mint)
                .amount(amount)
                .feeLamports(fee)
                .confirmations(0)
                .status(status)
                .build());
    }

    private void updateConfirmedTransaction(String signature, JsonNode status) {
        JsonNode transaction = rpc.getTransaction(signature);
        repository.recordSolanaTransaction(SolanaTransactionRecord.builder()
                .chain(CHAIN)
                .signature(signature)
                .fromAddress("")
                .toAddress("")
                .assetSymbol("SOL")
                .amount(BigDecimal.ZERO)
                .feeLamports(transaction.path("meta").path("fee").asLong())
                .slot(transaction.path("slot").asLong())
                .confirmations(status.path("confirmations").isNull() ? 32 : status.path("confirmations").asInt())
                .status("CONFIRMED")
                .rawPayload(transaction.toString())
                .build());
    }
}
