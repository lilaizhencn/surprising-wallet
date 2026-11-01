package com.surprising.wallet.chain.ton;

import com.surprising.wallet.chain.model.HotWalletRules;
import com.surprising.wallet.chain.model.TonTransactionRecord;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainScanHeightRepository;
import com.surprising.wallet.repository.DepositRecordRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.repository.TonTransactionRepository;
import com.surprising.wallet.service.DepositService;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellSlice;
import org.ton.ton4j.tlb.*;
import tools.jackson.databind.JsonNode;

/** TON and TEP-74 deposits from original transaction/message cells and configured-master wallets. */
@Service
@Slf4j
public class TonDepositScanner {

    /** TON 链标识 */
    private static final String CHAIN = "TON";

    /** 扫描器标识（用于持久化扫描高度） */
    private static final String SCANNER = "ton-account-message-scanner";

    /** TON 小数位数 */
    private static final int TON_DECIMALS = 9;

    /** 钱包角色：存款 */
    private static final String WALLET_ROLE_DEPOSIT = "DEPOSIT";

    /** 钱包角色：合约部署者 */
    private static final String WALLET_ROLE_CONTRACT_DEPLOYER = "CONTRACT_DEPLOYER";

    /** TEP-74 Jetton 转账通知 opcode */
    private static final long JETTON_TRANSFER_NOTIFICATION = 0x7362d09cL;

    /** TEP-74 Jetton 内部转账 opcode */
    private static final long JETTON_INTERNAL_TRANSFER = 0x178d4519L;

    /** Jetton 超额退款 opcode（运营性消息，不算充值） */
    private static final long JETTON_EXCESSES = 0xd53276dbL;

    /** 文本注释 opcode */
    private static final long TEXT_COMMENT = 0x00000000L;

    /** TON Center RPC 客户端 */
    private final TonCenterClient rpc;

    /** 地址服务（用于地址规范化比较） */
    private final TonAddressService addressService;
    private final ChainAddressRepository chainAddressRepository;
    private final ChainProfileRepository chainProfileRepository;
    private final TokenConfigRepository tokenConfigRepository;
    private final ChainScanHeightRepository chainScanHeightRepository;
    private final TonTransactionRepository tonTransactionRepository;
    private final DepositService depositService;
    private final DepositRecordRepository depositRecordRepository;


    /** 运行时任务开关服务。 */
    private final WalletRuntimeConfigService runtimeConfigService;

    public TonDepositScanner(
            TonCenterClient rpc,
            TonAddressService addressService,
            ChainAddressRepository chainAddressRepository,
            ChainProfileRepository chainProfileRepository,
            TokenConfigRepository tokenConfigRepository,
            ChainScanHeightRepository chainScanHeightRepository,
            TonTransactionRepository tonTransactionRepository,
            DepositService depositService,
            DepositRecordRepository depositRecordRepository,
            WalletRuntimeConfigService runtimeConfigService) {
        this.rpc = rpc;
        this.addressService = addressService;
        this.chainAddressRepository = chainAddressRepository;
        this.chainProfileRepository = chainProfileRepository;
        this.tokenConfigRepository = tokenConfigRepository;
        this.chainScanHeightRepository = chainScanHeightRepository;
        this.tonTransactionRepository = tonTransactionRepository;
        this.depositService = depositService;
        this.depositRecordRepository = depositRecordRepository;
        this.runtimeConfigService = java.util.Objects.requireNonNull(runtimeConfigService);
    }

    /**
     * 执行一次完整的充值扫描并触发入账。
     *
     * <p>先物化 Jetton Wallet 地址，再扫描原生 TON 和 Jetton 充值。
     *
     * @return 发现的充值事件列表
     */
    public List<DepositEvent> scanAndCredit() {
        runtimeConfigService.requireTaskEnabled(CHAIN, WalletRuntimeConfigService.TASK_SCAN, "ton scanAndCredit");
        AccountChainProfile profile = profile();
        long masterchainSeqno = rpc.masterchainInfo().path("last").path("seqno").asLong();
        if (masterchainSeqno <= 0) throw new IllegalStateException("TON deposit masterchain observation is absent");
        List<DepositEvent> events = new ArrayList<>();
        List<ChainAddressRecord> nativeAddresses = chainAddressRepository.listEnabled(CHAIN, "TON");
        List<TokenDefinition> tokens = chainProfileRepository.findAccountByChain(CHAIN).map( configuredNetworkProfile -> tokenConfigRepository.listDefinitions(CHAIN, configuredNetworkProfile.getNetwork())).orElse(List.of());
        materializeJettonWallets(nativeAddresses, tokens);
        Set<String> platformAddresses = platformAddresses();
        for (ChainAddressRecord address : nativeAddresses) {
            if (isNativeScanRole(address)) {
                scanNative(address, profile, masterchainSeqno, platformAddresses, events);
            }
        }
        for (TokenDefinition token : tokens) {
            for (ChainAddressRecord address : chainAddressRepository.listEnabled(CHAIN, token.getSymbol())) {
                if (WALLET_ROLE_DEPOSIT.equals(address.getWalletRole())) {
                    scanJetton(address, token, profile, masterchainSeqno, platformAddresses, events);
                }
            }
        }
        refreshPendingDeposits(profile, masterchainSeqno);
        long safeSeqno = Math.max(0L, masterchainSeqno - profile.getDepositConfirmations() + 1L);
        chainScanHeightRepository.upsert(CHAIN, SCANNER, masterchainSeqno, safeSeqno);
        return events;
    }

    private void scanNative(ChainAddressRecord tracked, AccountChainProfile profile,
                            long masterchainSeqno, Set<String> platformAddresses,
                            List<DepositEvent> events) {
        for (JsonNode tx : rpc.transactions(tracked.getAddress(), scanLimit(profile))) {
            var execution = TonExecutionReceipt.read(tx, tracked.getAddress());
            if (!(execution.incoming().message().getInfo() instanceof InternalMessageInfo info)
                    || Boolean.TRUE.equals(info.getBounced()) || info.getValueCoins().signum() <= 0
                    || !sameAddress(info.getDestinationAddress(), tracked.getAddress())
                    || isPlatformAddress(info.getSourceAddress(), platformAddresses)
                    || isOperationalNativeMessage(execution.incoming().message().getBody())
                    || !execution.outgoing().isEmpty()
                    || !(execution.transaction().getDescription() instanceof TransactionDescriptionOrdinary ordinary)
                    || ordinary.getCreditPhase() == null) continue;
            var credit = ordinary.getCreditPhase();
            BigInteger gross = credit.getCredit().getCoins().add(credit.getDueFeesCollected() == null ? BigInteger.ZERO : credit.getDueFeesCollected());
            if (!gross.equals(info.getValueCoins())) throw new IllegalStateException("TON deposit gross credit differs from raw internal principal");
            var event = event(tx, tracked, "TON", info.getSourceAddress(), tracked.getAddress(),
                    new BigDecimal(gross).movePointLeft(TON_DECIMALS), null, masterchainSeqno);
            if (depositRecordRepository.existsCanonical(CHAIN, event.txId(), 0)) continue;
            persist(event, tx, null, profile, tracked.getAccountId()); events.add(event);
        }
    }

    private void scanJetton(ChainAddressRecord tracked, TokenDefinition token,
                            AccountChainProfile profile, long masterchainSeqno,
                            Set<String> platformAddresses, List<DepositEvent> events) {
        if (!"active".equals(rpc.addressInformation(tracked.getAddress()).path("state").asText())) return;
        String master = addressService.normalizeRaw(token.getContractAddress()), owner = addressService.normalizeRaw(tracked.getOwnerAddress());
        var data = rpc.jettonData(tracked.getAddress());
        if (!master.equals(data.master()) || !owner.equals(data.owner())
                || !rpc.jettonWallet(owner, master).equals(addressService.normalizeRaw(tracked.getAddress())))
            throw new IllegalStateException("TON deposit wallet differs from configured master and published owner");
        for (JsonNode tx : rpc.transactions(tracked.getAddress(), scanLimit(profile))) {
            var execution = TonExecutionReceipt.read(tx, tracked.getAddress());
            if (!(execution.incoming().message().getInfo() instanceof InternalMessageInfo info)
                    || Boolean.TRUE.equals(info.getBounced()) || !sameAddress(info.getDestinationAddress(), tracked.getAddress())
                    || !(execution.transaction().getDescription() instanceof TransactionDescriptionOrdinary ordinary)
                    || ordinary.isAborted() || !(ordinary.getComputePhase() instanceof ComputePhaseVM vm) || !vm.isSuccess()
                    || (ordinary.getActionPhase() != null && !ordinary.getActionPhase().isSuccess())) continue;
            var body = CellSlice.beginParse(execution.incoming().message().getBody());
            if (body.getRestBits() < 96 || body.loadUint(32).longValue() != JETTON_INTERNAL_TRANSFER) continue;
            body.loadUint(64); BigInteger amount = body.loadCoins(); var sender = body.loadAddress();
            String from = sender == null ? info.getSourceAddress() : sender.toRaw();
            if (amount.signum() <= 0 || isPlatformAddress(from, platformAddresses)) continue;
            var event = event(tx, tracked, token.getSymbol(), from, tracked.getAddress(), new BigDecimal(amount).movePointLeft(token.getDecimals()), master, masterchainSeqno);
            if (depositRecordRepository.existsCanonical(CHAIN, event.txId(), 0)) continue;
            persist(event, tx, master, profile, tracked.getAccountId()); events.add(event);
        }
    }

    private DepositEvent event(JsonNode tx, ChainAddressRecord tracked, String symbol,
                               String source, String destination, BigDecimal amount, String master,
                               long masterchainSeqno) {
        return new DepositEvent(ChainType.TON, symbol,
                java.util.HexFormat.of().formatHex(Cell.fromBocBase64(tx.path("data").asText()).hash()),
                source, destination, amount,
                masterchainSeqno,
                tx.path("transaction_id").path("hash").asText(), 1, master, tx.toString());
    }

    private void persist(DepositEvent event, JsonNode tx, String master,
                         AccountChainProfile profile, String accountId) {
        tonTransactionRepository.upsert(TonTransactionRecord.builder()
                .chain(CHAIN)
                .txHash(event.txId())
                .fromAddress(event.fromAddress())
                .toAddress(event.toAddress())
                .assetSymbol(event.assetSymbol())
                .jettonMaster(master)
                .amount(event.amount())
                .feeNano(TonExecutionReceipt.read(tx,event.toAddress()).transaction().getTotalFees().getCoins().longValueExact())
                .logicalTime(new BigInteger(tx.path("transaction_id").path("lt").asText("0")))
                .confirmations(event.confirmations())
                .status(event.confirmations() >= profile.getDepositConfirmations()
                        ? "CONFIRMED" : "CONFIRMING")
                .rawPayload(tx.path("data").asText())
                .executionReceipt("{\"depositTransaction\":" + tx + "}")
                .build());
        depositService.recordAndCredit(event, 0, profile.getDepositConfirmations(), accountId);
    }
    private void refreshPendingDeposits(AccountChainProfile profile, long masterchainSeqno) {
        int requiredConfirmations = profile.getDepositConfirmations();
        for (var pending : depositRecordRepository.listPending(CHAIN, requiredConfirmations, 500)) {
            int confirmations = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(1L, masterchainSeqno - pending.blockHeight() + 1L));
            if (confirmations <= pending.confirmations()) {
                continue;
            }
            DepositEvent event = new DepositEvent(
                    ChainType.TON,
                    pending.assetSymbol(),
                    pending.txHash(),
                    pending.fromAddress(),
                    pending.toAddress(),
                    pending.amount(),
                    pending.blockHeight(),
                    pending.blockHash(),
                    confirmations,
                    pending.contractAddress(),
                    pending.rawPayload());
            depositService.recordAndCredit(event, pending.logIndex(), requiredConfirmations, pending.accountId());
            tonTransactionRepository.updateDepositConfirmations(CHAIN, pending.txHash(), confirmations, requiredConfirmations);
        }
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
        for (ChainAddressRecord tracked : chainAddressRepository.listEnabled(CHAIN)) {
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
        for (ChainAddressRecord owner : nativeAddresses) {
            if (!WALLET_ROLE_DEPOSIT.equals(owner.getWalletRole())
                    || HotWalletRules.isDefaultHotUser(owner.getUserId(), owner.getBiz())) {
                continue;
            }
            for (TokenDefinition token : tokens) {
                if (chainAddressRepository.find(
                        CHAIN,
                        token.getSymbol(),
                        owner.getUserId(),
                        owner.getBiz(),
                        owner.getAddressIndex(),
                        owner.getWalletRole()).isPresent()) {
                    continue;
                }
                try {
                    String wallet = rpc.jettonWallet(owner.getAddress(), token.getContractAddress());
                    addressService.registerJettonWallet(owner.getTenantId(), token.getSymbol(), wallet,
                            owner.getUserId(), owner.getBiz(), owner.getAddressIndex(), owner.getWalletRole());
                } catch (RuntimeException e) {
                    log.warn("TON Jetton wallet materialization failed: owner={} symbol={} error={}",
                            owner.getAddress(), token.getSymbol(), e.getMessage());
                }
            }
        }
    }
    private boolean isOperationalNativeMessage(Cell body) {
        try {
            CellSlice slice = CellSlice.beginParse(body);
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
    private AccountChainProfile profile() {
        return chainProfileRepository.findAccountByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
    private int scanLimit(AccountChainProfile profile) {
        Integer batchSize = profile.getScanBatchSize();
        return batchSize == null || batchSize <= 0 ? 100 : Math.min(100, batchSize);
    }
}
