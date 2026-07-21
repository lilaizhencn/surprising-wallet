package com.surprising.wallet.service.chain.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.SolanaTransactionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.core.Transaction;
import org.p2p.solanaj.programs.AssociatedTokenProgram;
import org.p2p.solanaj.programs.MemoProgram;
import org.p2p.solanaj.programs.SystemProgram;
import org.p2p.solanaj.programs.TokenProgram;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SolanaTransactionService {
    private static final String CHAIN = "SOLANA";
    private static final int SPL_MINT_ACCOUNT_LENGTH = 82;

    private final SolanaRpcClient rpc;
    private final SolanaKeyService keyService;
    private final SolanaAddressService addressService;
    private final ChainJdbcRepository repository;

    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    public String sendNative(long derivationIndex, String toAddress, long lamports) {
        if (lamports <= 0) {
            throw new IllegalArgumentException("lamports must be positive");
        }
        Account sender = keyService.account(derivationIndex);
        return sendNative(sender, toAddress, lamports);
    }

    public String sendNative(ChainAddressRecord from, String toAddress, long lamports) {
        if (lamports <= 0) {
            throw new IllegalArgumentException("lamports must be positive");
        }
        Account sender = keyService.account(from.getUserId(), from.getBiz(), from.getAddressIndex());
        return sendNative(sender, toAddress, lamports);
    }

    private String sendNative(Account sender, String toAddress, long lamports) {
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
        return sendToken(sender, mintAddress, toOwnerAddress, atomicAmount, decimals);
    }

    public String sendToken(ChainAddressRecord from, String mintAddress, String toOwnerAddress,
                            long atomicAmount, int decimals) {
        if (atomicAmount <= 0) {
            throw new IllegalArgumentException("token amount must be positive");
        }
        Account sender = keyService.account(from.getUserId(), from.getBiz(), from.getAddressIndex());
        return sendToken(sender, mintAddress, toOwnerAddress, atomicAmount, decimals);
    }

    public String sendTokenAmount(ChainAddressRecord from, String mintAddress, String toOwnerAddress,
                                  BigDecimal amount, int decimals) {
        ChainAddressRecord hot = defaultHotFeePayer();
        Account sourceOwner = keyService.account(from.getUserId(), from.getBiz(), from.getAddressIndex());
        Account feePayer = keyService.account(hot.getUserId(), hot.getBiz(), hot.getAddressIndex());
        return sendTokenWithFeePayer(sourceOwner, feePayer, mintAddress, toOwnerAddress,
                toAtomicAmount(amount, decimals), decimals);
    }

    private String sendToken(Account sender, String mintAddress, String toOwnerAddress,
                             long atomicAmount, int decimals) {
        return sendTokenWithFeePayer(sender, sender, mintAddress, toOwnerAddress, atomicAmount, decimals);
    }

    public DeploySplMintResult deploySplMint(ChainAddressRecord payerRecord,
                                             String ownerAddress,
                                             int decimals,
                                             long initialAtomicAmount,
                                             boolean retainMintAuthority,
                                             String memo) {
        if (decimals < 0 || decimals > 9) {
            throw new IllegalArgumentException("Solana SPL decimals must be between 0 and 9");
        }
        if (initialAtomicAmount < 0) {
            throw new IllegalArgumentException("Solana initial supply must be non-negative");
        }
        Account payer = keyService.account(payerRecord.getUserId(), payerRecord.getBiz(), payerRecord.getAddressIndex());
        Account mint = new Account();
        PublicKey owner = new PublicKey(ownerAddress);
        PublicKey ownerAta = new PublicKey(addressService.associatedTokenAddress(ownerAddress, mint.getPublicKeyBase58()));
        long rent = rpc.minimumBalanceForRentExemption(SPL_MINT_ACCOUNT_LENGTH);

        Transaction transaction = new Transaction()
                .addInstruction(SystemProgram.createAccount(
                        payer.getPublicKey(), mint.getPublicKey(), rent, SPL_MINT_ACCOUNT_LENGTH, TokenProgram.PROGRAM_ID))
                .addInstruction(TokenProgram.initializeMint(
                        mint.getPublicKey(), decimals, payer.getPublicKey(), payer.getPublicKey()))
                .addInstruction(AssociatedTokenProgram.createIdempotent(
                        payer.getPublicKey(), owner, mint.getPublicKey()));
        if (initialAtomicAmount > 0) {
            transaction.addInstruction(TokenProgram.mintTo(
                    mint.getPublicKey(), ownerAta, payer.getPublicKey(), initialAtomicAmount));
        }
        transaction.addInstruction(TokenProgram.setAuthority(
                mint.getPublicKey(),
                payer.getPublicKey(),
                retainMintAuthority ? owner : null,
                TokenProgram.AuthorityType.MINT_TOKENS));
        transaction.addInstruction(TokenProgram.setAuthority(
                mint.getPublicKey(),
                payer.getPublicKey(),
                null,
                TokenProgram.AuthorityType.FREEZE_ACCOUNT));
        if (memo != null && !memo.isBlank()) {
            transaction.addInstruction(MemoProgram.writeUtf8(payer.getPublicKey(), memo));
        }
        String signature = signAndSend(transaction, List.of(payer, mint));
        recordTransaction(signature, payer.getPublicKeyBase58(), mint.getPublicKeyBase58(),
                "SOL_CONTRACT", mint.getPublicKeyBase58(), BigDecimal.ZERO, profile().getDefaultFee(), "SENT");
        return new DeploySplMintResult(signature, mint.getPublicKeyBase58(), ownerAta.toBase58());
    }

    private String sendTokenWithFeePayer(Account sourceOwner, Account feePayer, String mintAddress,
                                         String toOwnerAddress, long atomicAmount, int decimals) {
        PublicKey mint = new PublicKey(mintAddress);
        PublicKey sourceAta = new PublicKey(addressService.associatedTokenAddress(
                sourceOwner.getPublicKeyBase58(), mintAddress));
        PublicKey destinationOwner = new PublicKey(toOwnerAddress);
        PublicKey destinationAta = new PublicKey(addressService.associatedTokenAddress(
                toOwnerAddress, mintAddress));

        Transaction transaction = new Transaction();
        if (rpc.getAccountInfo(destinationAta.toBase58()).isNull()) {
            transaction.addInstruction(AssociatedTokenProgram.createIdempotent(
                    feePayer.getPublicKey(), destinationOwner, mint));
        }
        transaction.addInstruction(TokenProgram.transferChecked(
                sourceAta, destinationAta, atomicAmount, (byte) decimals, sourceOwner.getPublicKey(), mint));
        return sourceOwner.getPublicKeyBase58().equals(feePayer.getPublicKeyBase58())
                ? signAndSend(transaction, List.of(sourceOwner))
                : signAndSend(transaction, List.of(feePayer, sourceOwner));
    }

    public String withdrawNative(String orderNo, long userId, ChainAddressRecord from,
                                 String toAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "solana withdrawNative");
        Optional<String> previous = repository.findWithdrawalTxHash(CHAIN, orderNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        long amountLamports = toAtomicAmount(amount, 9);
        long feeLamports = profile().getDefaultFee();
        BigDecimal fee = BigDecimal.valueOf(feeLamports).movePointLeft(9);
        int inserted = repository.createWithdrawalOrder(orderNo, userId, CHAIN, "SOL", toAddress,
                amount, fee);
        if (inserted == 0) {
            return repository.findWithdrawalTxHash(CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("withdrawal already claimed without tx hash"));
        }
        BigDecimal totalDebit = amount.add(fee);
        if (!repository.freezeLedgerBalance(CHAIN, "SOL", from.getAccountId(), totalDebit)) {
            repository.updateWithdrawalStatus(CHAIN, orderNo, "FAILED", from.getAddress(), null,
                    "insufficient available ledger balance");
            throw new IllegalStateException("insufficient SOL ledger balance");
        }
        repository.updateWithdrawalStatus(CHAIN, orderNo, "FROZEN", from.getAddress(), null, null);
        try {
            if (repository.claimWithdrawalSigning(CHAIN, orderNo, from.getAddress()) != 1) {
                throw new IllegalStateException("Solana withdrawal is not signable: " + orderNo);
            }
            String signature = sendNative(from, toAddress, amountLamports);
            if (repository.markWithdrawalSent(CHAIN, orderNo, from.getAddress(), signature) != 1) {
                throw new IllegalStateException("Solana withdrawal state changed before SENT: " + orderNo);
            }
            recordTransaction(signature, from.getAddress(), toAddress, "SOL", null,
                    amount, feeLamports, "SENT");
            return signature;
        } catch (RuntimeException e) {
            repository.markWithdrawalBroadcastUnknown(CHAIN, orderNo, from.getAddress(), e.getMessage());
            throw e;
        }
    }

    public String withdrawToken(String orderNo, long userId, ChainAddressRecord from,
                                String mintAddress, String toOwnerAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "solana withdrawToken");
        Optional<String> previous = repository.findWithdrawalTxHash(CHAIN, orderNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, mintAddress)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Solana mint " + mintAddress));
        int inserted = repository.createWithdrawalOrder(orderNo, userId, CHAIN, token.getSymbol(), toOwnerAddress,
                amount, BigDecimal.ZERO);
        if (inserted == 0) {
            return repository.findWithdrawalTxHash(CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("withdrawal already claimed without tx hash"));
        }
        if (!repository.freezeLedgerBalance(CHAIN, token.getSymbol(), from.getAccountId(), amount)) {
            repository.updateWithdrawalStatus(CHAIN, orderNo, "FAILED", from.getOwnerAddress(), null,
                    "insufficient available token ledger balance");
            throw new IllegalStateException("insufficient " + token.getSymbol() + " ledger balance");
        }
        repository.updateWithdrawalStatus(CHAIN, orderNo, "FROZEN", from.getOwnerAddress(), null, null);
        try {
            if (repository.claimWithdrawalSigning(CHAIN, orderNo, from.getOwnerAddress()) != 1) {
                throw new IllegalStateException("Solana token withdrawal is not signable: " + orderNo);
            }
            String signature = sendTokenAmount(from, mintAddress, toOwnerAddress, amount, token.getDecimals());
            if (repository.markWithdrawalSent(CHAIN, orderNo, from.getOwnerAddress(), signature) != 1) {
                throw new IllegalStateException("Solana token withdrawal state changed before SENT: " + orderNo);
            }
            recordTransaction(signature, from.getAddress(), toOwnerAddress, token.getSymbol(), mintAddress,
                    amount, profile().getDefaultFee(), "SENT");
            return signature;
        } catch (RuntimeException e) {
            repository.markWithdrawalBroadcastUnknown(CHAIN, orderNo, from.getOwnerAddress(), e.getMessage());
            throw e;
        }
    }

    public String collectNative(String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigDecimal amountLamports) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "solana collectNative");
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
            String signature = sendNative(from, hotAddress, amountLamports.longValueExact());
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
                               String hotOwnerAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "solana collectToken");
        Optional<String> previous = repository.findCollectionTxHash(CHAIN, collectionNo);
        if (previous.isPresent()) {
            return previous.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, mintAddress)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Solana mint " + mintAddress));
        repository.createCollectionRecord(collectionNo, CHAIN, token.getSymbol(), from.getAddress(),
                hotOwnerAddress, amount, BigDecimal.valueOf(profile().getDefaultFee()), null);
        if (repository.claimCollectionSigning(CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("collection is not retryable"));
        }
        try {
            ChainAddressRecord hot = repository.findChainAddressByAddress(CHAIN, "SOL", hotOwnerAddress)
                    .or(() -> repository.findChainAddressByAddress(CHAIN, hotOwnerAddress))
                    .orElseThrow(() -> new IllegalStateException("missing Solana hot fee payer " + hotOwnerAddress));
            Account sourceOwner = keyService.account(from.getUserId(), from.getBiz(), from.getAddressIndex());
            Account feePayer = keyService.account(hot.getUserId(), hot.getBiz(), hot.getAddressIndex());
            String signature = sendTokenWithFeePayer(sourceOwner, feePayer, mintAddress, hotOwnerAddress,
                    toAtomicAmount(amount, token.getDecimals()), token.getDecimals());
            repository.updateCollectionStatus(CHAIN, collectionNo, "SENT", signature, null, null);
            recordTransaction(signature, from.getAddress(), hotOwnerAddress, token.getSymbol(), mintAddress,
                    amount, profile().getDefaultFee(), "SENT");
            return signature;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public boolean confirmWithdrawal(String orderNo, String assetSymbol, String accountId, BigDecimal debitAmount) {
        String signature = repository.findWithdrawalTxHash(CHAIN, orderNo).orElseThrow();
        JsonNode status = requireSuccessfulConfirmation(signature, Duration.ofMinutes(2));
        if (repository.confirmWithdrawalAndSettle(CHAIN, orderNo, signature, assetSymbol, accountId, debitAmount)) {
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
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }

    private ChainAddressRecord defaultHotFeePayer() {
        return repository.listDefaultHotAddressCandidates(CHAIN, "SOL").stream()
                .filter(address -> Boolean.TRUE.equals(address.getEnabled()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing enabled Solana default hot fee payer"));
    }

    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }

    private long toAtomicAmount(BigDecimal amount, int decimals) {
        BigInteger atomic = amount.movePointRight(decimals).toBigIntegerExact();
        return atomic.longValueExact();
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

    public record DeploySplMintResult(String signature, String mintAddress, String ownerTokenAccount) {
    }
}
