package com.surprising.wallet.service.chain.ton;

import com.fasterxml.jackson.databind.JsonNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TonTransactionRecord;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellSlice;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public
class TonDepositScanner {
    private static final String CHAIN = "TON";
    private static final String SCANNER = "ton-account-message-scanner";
    private static final int TON_DECIMALS = 9;
    private static final String WALLET_ROLE_DEPOSIT = "DEPOSIT";
    private static final String WALLET_ROLE_CONTRACT_DEPLOYER = "CONTRACT_DEPLOYER";
    private static final long JETTON_TRANSFER_NOTIFICATION = 0x7362d09cL;
    private static final long JETTON_INTERNAL_TRANSFER = 0x178d4519L;
    private static final long JETTON_EXCESSES = 0xd53276dbL;
    private static final long TEXT_COMMENT = 0x00000000L;
    private final TonCenterClient rpc;
    private final TonAddressService addressService;
    private final ChainJdbcRepository repository;
    private final TonApiClient tonApi;

    @Autowired
    public TonDepositScanner(TonCenterClient rpc, TonAddressService addressService,
                             ChainJdbcRepository repository, TonApiClient tonApi) {
        this.rpc = rpc;
        this.addressService = addressService;
        this.repository = repository;
        this.tonApi = tonApi;
    }

    TonDepositScanner(TonCenterClient rpc, TonAddressService addressService,
                      ChainJdbcRepository repository) {
        this(rpc, addressService, repository, null);
    }

    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;
    public List<DepositEvent> scanAndCredit() {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_SCAN, "ton scanAndCredit");
        AccountChainProfile profile = profile();
        List<DepositEvent> events = new ArrayList<>();
        List<ChainAddressRecord> nativeAddresses = repository.listChainAddresses(CHAIN, "TON");
        List<TokenDefinition> tokens = repository.listTokens(CHAIN);
        Set<String> platformAddresses = platformAddresses();
        for (ChainAddressRecord address : nativeAddresses) {
            if (isNativeScanRole(address)) {
                scanNative(address, profile, platformAddresses, events);
            }
        }
        materializeJettonWallets(nativeAddresses, tokens);
        for (TokenDefinition token : tokens) {
            for (ChainAddressRecord address : repository.listChainAddresses(CHAIN, token.getSymbol())) {
                if (WALLET_ROLE_DEPOSIT.equals(address.getWalletRole())) {
                    scanJetton(address, token, profile, platformAddresses, events);
                }
            }
        }
        long masterchainSeqno = rpc.masterchainInfo().path("last").path("seqno").asLong();
        repository.updateScanHeight(CHAIN, SCANNER, masterchainSeqno, masterchainSeqno);
        return events;
    }

    private void scanNative(ChainAddressRecord tracked, AccountChainProfile profile,
                            Set<String> platformAddresses, List<DepositEvent> events) {
        JsonNode transactions = rpc.transactions(tracked.getAddress(), scanLimit(profile));
        for (JsonNode tx : transactions) {
            JsonNode in = tx.path("in_msg");
            String destination = in.path("destination").asText();
            String source = in.path("source").asText();
            BigDecimal amount = displayAmount(decimal(in.path("value").asText()), TON_DECIMALS);
            if (destination.isBlank() || source.isBlank() || amount.signum() <= 0
                    || !sameAddress(destination, tracked.getAddress())
                    || isPlatformAddress(source, platformAddresses)
                    || isOperationalNativeMessage(in.path("msg_data").path("body").asText())) {
                continue;
            }
            DepositEvent event = event(tx, tracked, "TON", source, destination, amount, null);
            persist(event, tx, null, profile, tracked.getAccountId());
            events.add(event);
        }
    }

    private void scanJetton(ChainAddressRecord tracked, TokenDefinition token,
                            AccountChainProfile profile, Set<String> platformAddresses,
                            List<DepositEvent> events) {
        JsonNode transactions = rpc.transactions(tracked.getAddress(), scanLimit(profile));
        for (JsonNode tx : transactions) {
            JsonNode in = tx.path("in_msg");
            if (!sameAddress(in.path("destination").asText(), tracked.getAddress())) {
                continue;
            }
            JettonNotification notification = parseJettonDepositBody(
                    in.path("msg_data").path("body").asText());
            if (notification == null || notification.amount().signum() <= 0
                    || isPlatformAddress(notification.sender(), platformAddresses)) {
                continue;
            }
            DepositEvent event = event(tx, tracked, token.getSymbol(), notification.sender(),
                    tracked.getAddress(), displayAmount(new BigDecimal(notification.amount()), token.getDecimals()),
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
                        isTestnet());
            } else if (opcode == JETTON_INTERNAL_TRANSFER) {
                sender = slice.loadAddress().toString(true, true, true,
                        isTestnet());
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
                tx.path("transaction_id").path("hash").asText(), 1, master, tx.toString());
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
    private boolean isPlatformAddress(String address, Set<String> platformAddresses) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            return platformAddresses.contains(addressService.normalizeRaw(address));
        } catch (RuntimeException ignored) {
            return false;
        }
    }
    private Set<String> platformAddresses() {
        Set<String> addresses = new HashSet<>();
        for (ChainAddressRecord tracked : repository.listChainAddresses(CHAIN)) {
            addNormalized(addresses, tracked.getAddress());
            addNormalized(addresses, tracked.getOwnerAddress());
        }
        return addresses;
    }
    private void addNormalized(Set<String> addresses, String address) {
        if (address == null || address.isBlank()) {
            return;
        }
        try {
            addresses.add(addressService.normalizeRaw(address));
        } catch (RuntimeException ignored) {
            // Ignore malformed historical rows; valid tracked addresses are still scanned.
        }
    }

    private void materializeJettonWallets(List<ChainAddressRecord> nativeAddresses,
                                          List<TokenDefinition> tokens) {
        if (tonApi == null) {
            return;
        }
        for (ChainAddressRecord owner : nativeAddresses) {
            if (!WALLET_ROLE_DEPOSIT.equals(owner.getWalletRole())
                    || HotWalletRules.isDefaultHotUser(owner.getUserId(), owner.getBiz())) {
                continue;
            }
            for (TokenDefinition token : tokens) {
                if (repository.findChainAddress(CHAIN, token.getSymbol(), owner.getUserId(), owner.getBiz(),
                        owner.getAddressIndex(), owner.getWalletRole()).isPresent()) {
                    continue;
                }
                try {
                    String wallet = tonApi.resolveJettonWallet(owner.getAddress(), token.getContractAddress());
                    addressService.registerJettonWallet(owner.getTenantId(), token.getSymbol(), wallet,
                            owner.getUserId(), owner.getBiz(), owner.getAddressIndex(), owner.getWalletRole());
                } catch (RuntimeException e) {
                    log.warn("TON Jetton wallet materialization failed: owner={} symbol={} error={}",
                            owner.getAddress(), token.getSymbol(), e.getMessage());
                }
            }
        }
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
    private static boolean isNativeScanRole(ChainAddressRecord address) {
        if (address == null) {
            return false;
        }
        return WALLET_ROLE_DEPOSIT.equals(address.getWalletRole())
                || WALLET_ROLE_CONTRACT_DEPLOYER.equals(address.getWalletRole());
    }
    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }
    private static BigDecimal displayAmount(BigDecimal atomicAmount, int decimals) {
        return atomicAmount.movePointLeft(decimals);
    }
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
    private int scanLimit(AccountChainProfile profile) {
        Integer batchSize = profile.getScanBatchSize();
        return batchSize == null || batchSize <= 0 ? 100 : batchSize;
    }
    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }
    private boolean isTestnet() {
        if (repository == null) {
            return true;
        }
        return profile().getNetwork().toLowerCase(java.util.Locale.ROOT).contains("test");
    }
    record JettonNotification(BigInteger amount, String sender) {
    }
}
