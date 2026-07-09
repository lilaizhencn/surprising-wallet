package com.surprising.wallet.service.chain.ton;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TonTransactionRecord;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.smartcontract.SendMode;
import org.ton.ton4j.smartcontract.token.ft.JettonWalletV2;
import org.ton.ton4j.smartcontract.types.WalletV4R2Config;
import org.ton.ton4j.smartcontract.utils.MsgUtils;
import org.ton.ton4j.smartcontract.wallet.v4.WalletV4R2;
import org.ton.ton4j.tlb.Message;
import org.ton.ton4j.tlb.StateInit;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TonTransactionService {
    private static final String CHAIN = "TON";
    private static final BigInteger JETTON_FORWARD_TON = BigInteger.valueOf(10_000_000L);
    private static final BigInteger JETTON_GAS_TON = BigInteger.valueOf(70_000_000L);

    private final TonCenterClient rpc;
    private final TonKeyService keyService;
    private final ChainJdbcRepository repository;

    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;

    public PreparedTransfer prepareNative(long derivationIndex, String toAddress,
                                          BigInteger amountNano, String comment) {
        return prepareNative(0L, 0, derivationIndex, toAddress, amountNano, comment);
    }

    public PreparedTransfer prepareNative(ChainAddressRecord from, String toAddress,
                                          BigInteger amountNano, String comment) {
        return prepareNative(from.getUserId(), from.getBiz(), from.getAddressIndex(),
                toAddress, amountNano, comment);
    }

    private PreparedTransfer prepareNative(long userId, int biz, long derivationIndex,
                                           String toAddress, BigInteger amountNano, String comment) {
        WalletV4R2 wallet = keyService.wallet(userId, biz, derivationIndex);
        String from = friendly(wallet.getAddress(), false);
        long chainSeqno = rpc.seqno(from);
        long seqno = repository.reserveAccountSequence(CHAIN, from, chainSeqno);
        WalletV4R2Config config = WalletV4R2Config.builder()
                .walletId(TonKeyService.WALLET_V4R2_SUBWALLET_ID)
                .seqno(seqno)
                .bounce(false)
                .destination(Address.of(toAddress))
                .amount(amountNano)
                .comment(comment)
                .sendMode(SendMode.PAY_GAS_SEPARATELY)
                .build();
        return prepare(wallet, config, seqno);
    }

    public PreparedTransfer prepareJetton(long derivationIndex, String sourceJettonWallet,
                                          String destinationOwner, BigInteger tokenAmount,
                                          String responseAddress, String comment) {
        return prepareJetton(0L, 0, derivationIndex, sourceJettonWallet,
                destinationOwner, tokenAmount, responseAddress, comment);
    }

    public PreparedTransfer prepareJetton(ChainAddressRecord from, String sourceJettonWallet,
                                          String destinationOwner, BigInteger tokenAmount,
                                          String responseAddress, String comment) {
        return prepareJetton(from.getUserId(), from.getBiz(), from.getAddressIndex(),
                sourceJettonWallet, destinationOwner, tokenAmount, responseAddress, comment);
    }

    private PreparedTransfer prepareJetton(long userId, int biz, long derivationIndex, String sourceJettonWallet,
                                           String destinationOwner, BigInteger tokenAmount,
                                           String responseAddress, String comment) {
        WalletV4R2 wallet = keyService.wallet(userId, biz, derivationIndex);
        String from = friendly(wallet.getAddress(), false);
        long chainSeqno = rpc.seqno(from);
        long seqno = repository.reserveAccountSequence(CHAIN, from, chainSeqno);
        Cell forwardPayload = comment == null || comment.isBlank()
                ? null : MsgUtils.createTextMessageBody(comment);
        Cell jettonBody = JettonWalletV2.createTransferBody(
                System.nanoTime(), tokenAmount, Address.of(destinationOwner), Address.of(responseAddress),
                null, JETTON_FORWARD_TON, forwardPayload);
        WalletV4R2Config config = WalletV4R2Config.builder()
                .walletId(TonKeyService.WALLET_V4R2_SUBWALLET_ID)
                .seqno(seqno)
                .bounce(true)
                .destination(Address.of(sourceJettonWallet))
                .amount(JETTON_GAS_TON)
                .body(jettonBody)
                .sendMode(SendMode.PAY_GAS_SEPARATELY)
                .build();
        return prepare(wallet, config, seqno);
    }

    public PreparedTransfer prepareWalletDeploy(long derivationIndex) {
        WalletV4R2 wallet = keyService.wallet(derivationIndex);
        Message message = wallet.prepareDeployMsg();
        byte[] boc = message.toCell().toBoc(false);
        return new PreparedTransfer(0, boc,
                java.util.Base64.getEncoder().encodeToString(boc),
                java.util.HexFormat.of().formatHex(message.toCell().hash()));
    }

    public PreparedTransfer prepareContractCall(long derivationIndex, String destination,
                                                BigInteger amountNano, StateInit stateInit,
                                                Cell body, boolean bounce) {
        WalletV4R2 wallet = keyService.wallet(derivationIndex);
        return prepareContractCall(wallet, destination, amountNano, stateInit, body, bounce);
    }

    public PreparedTransfer prepareContractCall(ChainAddressRecord from, String destination,
                                                BigInteger amountNano, StateInit stateInit,
                                                Cell body, boolean bounce) {
        WalletV4R2 wallet = keyService.wallet(from.getUserId(), from.getBiz(), from.getAddressIndex());
        return prepareContractCall(wallet, destination, amountNano, stateInit, body, bounce);
    }

    private PreparedTransfer prepareContractCall(WalletV4R2 wallet, String destination,
                                                 BigInteger amountNano, StateInit stateInit,
                                                 Cell body, boolean bounce) {
        String from = friendly(wallet.getAddress(), false);
        long chainSeqno = rpc.seqno(from);
        long seqno = repository.reserveAccountSequence(CHAIN, from, chainSeqno);
        WalletV4R2Config config = WalletV4R2Config.builder()
                .walletId(TonKeyService.WALLET_V4R2_SUBWALLET_ID)
                .seqno(seqno)
                .bounce(bounce)
                .destination(Address.of(destination))
                .amount(amountNano)
                .stateInit(stateInit)
                .body(body)
                .sendMode(SendMode.PAY_GAS_SEPARATELY)
                .build();
        return prepare(wallet, config, seqno);
    }

    public String broadcast(PreparedTransfer transfer) {
        return rpc.sendBoc(transfer.boc());
    }

    public String withdrawNative(String orderNo, long userId, ChainAddressRecord from,
                                 String toAddress, BigDecimal amountNano, String memo) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "ton withdrawNative");
        Optional<String> existing = repository.findWithdrawalTxHash(CHAIN, orderNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        long fee = profile().getDefaultFee();
        if (repository.createWithdrawalOrder(orderNo, userId, CHAIN, "TON", toAddress,
                amountNano, BigDecimal.valueOf(fee)) == 0) {
            return repository.findWithdrawalTxHash(CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("TON withdrawal already claimed"));
        }
        BigDecimal debit = amountNano.add(BigDecimal.valueOf(fee));
        if (!repository.freezeLedgerBalance(CHAIN, "TON", from.getAccountId(), debit)) {
            repository.updateWithdrawalStatus(CHAIN, orderNo, "FAILED", from.getAddress(), null,
                    "insufficient TON ledger balance");
            throw new IllegalStateException("insufficient TON ledger balance");
        }
        repository.updateWithdrawalStatus(CHAIN, orderNo, "FROZEN", from.getAddress(), null, null);
        try {
            if (repository.claimWithdrawalSigning(CHAIN, orderNo, from.getAddress()) != 1) {
                throw new IllegalStateException("TON withdrawal is not signable: " + orderNo);
            }
            PreparedTransfer prepared = prepareNative(from, toAddress, amountNano.toBigIntegerExact(), memo);
            String hash = broadcast(prepared);
            if (repository.markWithdrawalSent(CHAIN, orderNo, from.getAddress(), hash) != 1) {
                throw new IllegalStateException("TON withdrawal state changed before SENT: " + orderNo);
            }
            record(hash, from.getAddress(), toAddress, "TON", null, amountNano, fee,
                    null, "SENT", prepared.bocBase64());
            return hash;
        } catch (RuntimeException e) {
            repository.markWithdrawalBroadcastUnknown(CHAIN, orderNo, from.getAddress(), e.getMessage());
            throw e;
        }
    }

    public String withdrawJetton(String orderNo, long userId, ChainAddressRecord from,
                                 String jettonMaster, String destinationOwner,
                                 BigDecimal atomicAmount, String memo) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_WITHDRAW, "ton withdrawJetton");
        Optional<String> existing = repository.findWithdrawalTxHash(CHAIN, orderNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, jettonMaster)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Jetton " + jettonMaster));
        if (repository.createWithdrawalOrder(orderNo, userId, CHAIN, token.getSymbol(), destinationOwner,
                atomicAmount, BigDecimal.ZERO) == 0) {
            return repository.findWithdrawalTxHash(CHAIN, orderNo)
                    .orElseThrow(() -> new IllegalStateException("Jetton withdrawal already claimed"));
        }
        if (!repository.freezeLedgerBalance(CHAIN, token.getSymbol(), from.getAccountId(), atomicAmount)) {
            repository.updateWithdrawalStatus(CHAIN, orderNo, "FAILED", from.getOwnerAddress(), null,
                    "insufficient Jetton ledger balance");
            throw new IllegalStateException("insufficient Jetton ledger balance");
        }
        repository.updateWithdrawalStatus(CHAIN, orderNo, "FROZEN", from.getOwnerAddress(), null, null);
        try {
            if (repository.claimWithdrawalSigning(CHAIN, orderNo, from.getOwnerAddress()) != 1) {
                throw new IllegalStateException("Jetton withdrawal is not signable: " + orderNo);
            }
            PreparedTransfer prepared = prepareJetton(from, from.getAddress(),
                    destinationOwner, atomicAmount.toBigIntegerExact(), from.getOwnerAddress(), memo);
            String hash = broadcast(prepared);
            if (repository.markWithdrawalSent(CHAIN, orderNo, from.getOwnerAddress(), hash) != 1) {
                throw new IllegalStateException("Jetton withdrawal state changed before SENT: " + orderNo);
            }
            record(hash, from.getAddress(), destinationOwner, token.getSymbol(), jettonMaster,
                    atomicAmount, JETTON_GAS_TON.longValue(), null,
                    "SENT", prepared.bocBase64());
            return hash;
        } catch (RuntimeException e) {
            repository.markWithdrawalBroadcastUnknown(CHAIN, orderNo, from.getOwnerAddress(), e.getMessage());
            throw e;
        }
    }

    public boolean confirmWithdrawal(String orderNo, String symbol, String accountId,
                                     BigDecimal debitAmount, long reservedSeqno) {
        String hash = repository.findWithdrawalTxHash(CHAIN, orderNo).orElseThrow();
        long chainSeqno = rpc.seqno(accountId);
        if (chainSeqno <= reservedSeqno) {
            return false;
        }
        if (repository.confirmWithdrawalAndSettle(CHAIN, orderNo, hash, symbol, accountId, debitAmount)) {
            repository.markTonTransactionConfirmed(CHAIN, hash);
            return true;
        }
        return false;
    }

    public String collectNative(String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigDecimal amountNano, String memo) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "ton collectNative");
        Optional<String> existing = repository.findCollectionTxHash(CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        long fee = profile().getDefaultFee();
        repository.createCollectionRecord(collectionNo, CHAIN, "TON", from.getAddress(), hotAddress,
                amountNano, BigDecimal.valueOf(fee), null);
        if (repository.claimCollectionSigning(CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("TON collection is not retryable"));
        }
        try {
            PreparedTransfer prepared = prepareNative(from, hotAddress, amountNano.toBigIntegerExact(), memo);
            String hash = broadcast(prepared);
            repository.updateCollectionStatus(CHAIN, collectionNo, "SENT", hash, null, prepared.bocBase64());
            record(hash, from.getAddress(), hotAddress, "TON", null, amountNano, fee,
                    null, "SENT", prepared.bocBase64());
            return hash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public String collectJetton(String collectionNo, ChainAddressRecord from, String jettonMaster,
                                String hotOwnerAddress, BigDecimal atomicAmount, String memo) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "ton collectJetton");
        Optional<String> existing = repository.findCollectionTxHash(CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        TokenDefinition token = repository.findTokenByContract(CHAIN, jettonMaster)
                .orElseThrow(() -> new IllegalArgumentException("unconfigured Jetton " + jettonMaster));
        repository.createCollectionRecord(collectionNo, CHAIN, token.getSymbol(), from.getAddress(),
                hotOwnerAddress, atomicAmount, BigDecimal.valueOf(JETTON_GAS_TON.longValue()), null);
        if (repository.claimCollectionSigning(CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("TON Jetton collection is not retryable"));
        }
        try {
            PreparedTransfer prepared = prepareJetton(from, from.getAddress(),
                    hotOwnerAddress, atomicAmount.toBigIntegerExact(), from.getOwnerAddress(), memo);
            String hash = broadcast(prepared);
            repository.updateCollectionStatus(CHAIN, collectionNo, "SENT", hash, null, prepared.bocBase64());
            record(hash, from.getAddress(), hotOwnerAddress, token.getSymbol(), jettonMaster,
                    atomicAmount, JETTON_GAS_TON.longValue(), null, "SENT", prepared.bocBase64());
            return hash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }

    public boolean confirmCollection(String collectionNo) {
        String hash = repository.findCollectionTxHash(CHAIN, collectionNo).orElseThrow();
        if (repository.markCollectionConfirmed(CHAIN, collectionNo, hash) == 1) {
            repository.markTonTransactionConfirmed(CHAIN, hash);
            return true;
        }
        return false;
    }

    private PreparedTransfer prepare(WalletV4R2 wallet, WalletV4R2Config config, long seqno) {
        Message message = wallet.prepareExternalMsg(config);
        byte[] boc = message.toCell().toBoc(false);
        return new PreparedTransfer(seqno, boc,
                java.util.Base64.getEncoder().encodeToString(boc),
                java.util.HexFormat.of().formatHex(message.toCell().hash()));
    }

    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }

    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }

    private void record(String hash, String from, String to, String symbol, String master,
                        BigDecimal amount, long fee, BigInteger lt, String status, String rawPayload) {
        repository.recordTonTransaction(TonTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(hash)
                .fromAddress(from)
                .toAddress(to)
                .assetSymbol(symbol)
                .jettonMaster(master)
                .amount(amount)
                .feeNano(fee)
                .logicalTime(lt)
                .confirmations(0)
                .status(status)
                .rawPayload(rawPayload)
                .build());
    }

    private String friendly(Address address, boolean bounceable) {
        boolean testnet = profile().getNetwork().toLowerCase(java.util.Locale.ROOT).contains("test");
        return address.toString(true, true, bounceable, testnet);
    }

    public record PreparedTransfer(long seqno, byte[] boc, String bocBase64, String messageHashHex) {
    }
}
