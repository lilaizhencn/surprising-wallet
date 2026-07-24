package com.surprising.wallet.chain.ton;

import com.fasterxml.jackson.databind.JsonNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.TonTransactionRecord;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
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

/**
 * TON 充值扫描器，通过解析交易消息的 Cell body 识别充值。
 *
 * <p>扫描流程：
 * <ol>
 *   <li>获取主链最新 seqno 作为扫描高度</li>
 *   <li>对每个 DEPOSIT 或 CONTRACT_DEPLOYER 角色的原生 TON 地址，
 *       调用 getTransactions 获取交易列表</li>
 *   <li>扫描原生 TON 充值：检查 in_msg.destination 匹配、value > 0、
 *       非平台内部地址、非运营性消息（Jetton excesses/internal_transfer 等 opcode）</li>
 *   <li>自动物化 Jetton Wallet 地址（通过 {@link TonApiClient#resolveJettonWallet}）</li>
 *   <li>扫描 Jetton 充值：解析 in_msg.body 中的 Cell 结构，
 *       匹配 TEP-74 的 jetton_transfer_notification (0x7362d09c) 或
 *       jetton_internal_transfer (0x178d4519) opcode，提取 amount 和 sender</li>
 *   <li>过滤平台内部地址间的转账</li>
 *   <li>记录交易并触发入账</li>
 * </ol>
 *
 * <p>TON 精度为 9 位小数（1 TON = 1,000,000,000 nanoTON）。
 * 地址比较统一使用 raw 格式（{@link TonAddressService#normalizeRaw}）。
 *
 * @see TonCenterClient#transactions(String, int)
 * @see TonAddressService
 */
@Service
@Slf4j
public
class TonDepositScanner {

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

    /** 链配置数据库访问 */
    private final ChainJdbcRepository repository;

    /** TON API 索引器客户端（用于 Jetton Wallet 物化） */
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

    /**
     * 执行一次完整的充值扫描并触发入账。
     *
     * <p>先物化 Jetton Wallet 地址，再扫描原生 TON 和 Jetton 充值。
     *
     * @return 发现的充值事件列表
     */
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
    /**
     * Jetton 转账通知内容。
     *
     * @param amount 转账金额（原子单位）
     * @param sender 发送方地址
     */
    record JettonNotification(BigInteger amount, String sender) {
    }
}
