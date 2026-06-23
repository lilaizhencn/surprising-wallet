package com.surprising.wallet.service.chain.ton;

import com.fasterxml.jackson.databind.JsonNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TonTransactionRecord;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellSlice;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TonDepositScanner {
    private static final String CHAIN = "TON";
    private static final String SCANNER = "ton-account-message-scanner";
    private static final long JETTON_TRANSFER_NOTIFICATION = 0x7362d09cL;
    private static final long JETTON_INTERNAL_TRANSFER = 0x178d4519L;
    private static final long JETTON_EXCESSES = 0xd53276dbL;
    private static final long TEXT_COMMENT = 0x00000000L;

    private final TonCenterClient rpc;
    private final TonAddressService addressService;
    private final ChainJdbcRepository repository;

    @Value("${atomex.ton.network:testnet}")
    private String network = "testnet";

    @Value("${atomex.ton.scan-limit:100}")
    private int scanLimit = 100;

    public List<DepositEvent> scanAndCredit() {
        AccountChainProfile profile = repository.findAccountChainProfile(CHAIN, network)
                .orElseThrow(() -> new IllegalStateException("missing enabled TON/" + network + " profile"));
        List<DepositEvent> events = new ArrayList<>();
        for (ChainAddressRecord address : repository.listChainAddresses(CHAIN, "TON")) {
            if ("DEPOSIT".equals(address.getWalletRole())) {
                scanNative(address, profile, events);
            }
        }
        for (TokenDefinition token : repository.listTokens(CHAIN)) {
            for (ChainAddressRecord address : repository.listChainAddresses(CHAIN, token.getSymbol())) {
                if ("DEPOSIT".equals(address.getWalletRole())) {
                    scanJetton(address, token, profile, events);
                }
            }
        }
        long masterchainSeqno = rpc.masterchainInfo().path("last").path("seqno").asLong();
        repository.updateScanHeight(CHAIN, SCANNER, masterchainSeqno, masterchainSeqno);
        return events;
    }

    private void scanNative(ChainAddressRecord tracked, AccountChainProfile profile, List<DepositEvent> events) {
        JsonNode transactions = rpc.transactions(tracked.getAddress(), scanLimit);
        for (JsonNode tx : transactions) {
            JsonNode in = tx.path("in_msg");
            String destination = in.path("destination").asText();
            String source = in.path("source").asText();
            BigDecimal amount = decimal(in.path("value").asText());
            if (destination.isBlank() || source.isBlank() || amount.signum() <= 0
                    || !sameAddress(destination, tracked.getAddress())
                    || isPlatformAddress(source)
                    || isOperationalNativeMessage(in.path("msg_data").path("body").asText())) {
                continue;
            }
            DepositEvent event = event(tx, tracked, "TON", source, destination, amount, null);
            persist(event, tx, null, profile, tracked.getAccountId());
            events.add(event);
        }
    }

    private void scanJetton(ChainAddressRecord tracked, TokenDefinition token,
                            AccountChainProfile profile, List<DepositEvent> events) {
        JsonNode transactions = rpc.transactions(tracked.getAddress(), scanLimit);
        for (JsonNode tx : transactions) {
            JsonNode in = tx.path("in_msg");
            if (!sameAddress(in.path("destination").asText(), tracked.getAddress())) {
                continue;
            }
            JettonNotification notification = parseJettonDepositBody(
                    in.path("msg_data").path("body").asText());
            if (notification == null || notification.amount().signum() <= 0) {
                continue;
            }
            DepositEvent event = event(tx, tracked, token.getSymbol(), notification.sender(),
                    tracked.getAddress(), new BigDecimal(notification.amount()),
                    token.getContractAddress());
            persist(event, tx, token.getContractAddress(), profile, tracked.getAccountId());
            events.add(event);
        }
    }

    JettonNotification parseJettonNotification(String bodyBase64) {
        return parseJettonDepositBody(bodyBase64);
    }

    JettonNotification parseJettonDepositBody(String bodyBase64) {
        if (bodyBase64 == null || bodyBase64.isBlank()) {
            return null;
        }
        try {
            CellSlice slice = CellSlice.beginParse(Cell.fromBocBase64(bodyBase64));
            long opcode = slice.loadUint(32).longValue();
            slice.loadUint(64);
            BigInteger amount = slice.loadCoins();
            String sender;
            if (opcode == JETTON_TRANSFER_NOTIFICATION) {
                sender = slice.loadAddress().toString(true, true, true,
                        "testnet".equalsIgnoreCase(network));
            } else if (opcode == JETTON_INTERNAL_TRANSFER) {
                sender = slice.loadAddress().toString(true, true, true,
                        "testnet".equalsIgnoreCase(network));
            } else {
                return null;
            }
            return new JettonNotification(amount, sender);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private DepositEvent event(JsonNode tx, ChainAddressRecord tracked, String symbol,
                               String source, String destination, BigDecimal amount, String master) {
        return new DepositEvent(ChainType.TON, symbol,
                tx.path("transaction_id").path("hash").asText(),
                source, destination, amount,
                tx.path("transaction_id").path("lt").asLong(),
                1, master, tx.toString());
    }

    private void persist(DepositEvent event, JsonNode tx, String master,
                         AccountChainProfile profile, String accountId) {
        repository.recordTonTransaction(TonTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(event.txId())
                .fromAddress(event.fromAddress())
                .toAddress(event.toAddress())
                .assetSymbol(event.assetSymbol())
                .jettonMaster(master)
                .amount(event.amount())
                .feeNano(decimal(tx.path("fee").asText()).longValue())
                .logicalTime(new BigInteger(tx.path("transaction_id").path("lt").asText("0")))
                .confirmations(1)
                .status("CONFIRMED")
                .rawPayload(tx.toString())
                .build());
        repository.recordAndCreditDeposit(event, 0, profile.getDepositConfirmations(), accountId);
    }

    private boolean sameAddress(String first, String second) {
        if (first == null || first.isBlank() || second == null || second.isBlank()) {
            return false;
        }
        try {
            return addressService.normalizeRaw(first).equals(addressService.normalizeRaw(second));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isPlatformAddress(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        for (ChainAddressRecord tracked : repository.listChainAddresses(CHAIN)) {
            if (sameAddress(address, tracked.getAddress()) || sameAddress(address, tracked.getOwnerAddress())) {
                return true;
            }
        }
        return false;
    }

    private boolean isOperationalNativeMessage(String bodyBase64) {
        if (bodyBase64 == null || bodyBase64.isBlank()) {
            return false;
        }
        try {
            CellSlice slice = CellSlice.beginParse(Cell.fromBocBase64(bodyBase64));
            if (slice.getRestBits() < 32) {
                return false;
            }
            long opcode = slice.loadUint(32).longValue();
            return opcode == JETTON_EXCESSES
                    || opcode == JETTON_INTERNAL_TRANSFER
                    || opcode == JETTON_TRANSFER_NOTIFICATION;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    record JettonNotification(BigInteger amount, String sender) {
    }
}
