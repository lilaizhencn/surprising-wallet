package com.surprising.wallet.chain.cardano;

import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.backend.model.AddressTransactionContent;
import com.bloxbean.cardano.client.backend.model.TxContentOutputAmount;
import com.bloxbean.cardano.client.backend.model.TxContentUtxo;
import com.bloxbean.cardano.client.backend.model.TxContentUtxoInputs;
import com.bloxbean.cardano.client.backend.model.TxContentUtxoOutputs;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cardano 链充值扫描器，通过 Blockfrost API 扫描地址交易中的 UTxO 输出识别充值。
 *
 * <p>扫描策略：遍历平台托管的存款地址，拉取最近交易，分析 UTxO 输出中
 * 以平台地址为收款方的 lovelace（ADA）和自定义资产（native asset），
 * 排除平台内部转账后记录为充值事件。</p>
 *
 * @see CardanoBackendClient
 */
@Service
@RequiredArgsConstructor
public
class CardanoDepositScanner {

    /** 链标识 */
    private static final String CHAIN = CardanoBackendClient.CHAIN;

    /** 原生币符号 */
    private static final String SYMBOL = "ADA";

    /** 扫描器名称 */
    private static final String SCANNER = "cardano-address-scanner";

    /** ADA 的小数位数（1 ADA = 10^6 lovelace） */
    private static final int ADA_DECIMALS = 6;

    /** Blockfrost 后端客户端 */
    private final CardanoBackendClient backendClient;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /** 运行时配置服务（可选） */
    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;
    public List<DepositEvent> scanAndCredit() {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_SCAN, "cardano scanAndCredit");
        Map<String, TokenDefinition> tokensByUnit = tokensByUnit();
        Map<String, TrackedCardanoAddress> addresses = trackedDepositAddresses(tokensByUnit);
        if (addresses.isEmpty()) {
            return List.of();
        }
        Set<String> managedAddresses = repository.listChainAddresses(CHAIN).stream()
                .map(ChainAddressRecord::getAddress)
                .map(CardanoDepositScanner::normalize)
                .collect(Collectors.toSet());
        return backendClient.withBackend((backend, node, profile) -> {
            long latest = CardanoBackendClient.requireSuccess(
                    backend.getBlockService().getLatestBlock(), "latest block").getHeight();
            int requiredConfirmations = requiredConfirmations(profile);
            List<DepositEvent> events = new ArrayList<>();
            for (TrackedCardanoAddress address : addresses.values()) {
                List<AddressTransactionContent> txs = CardanoBackendClient.requireSuccess(
                        backend.getAddressService().getTransactions(address.address(),
                                scanLimit(profile), 1, OrderEnum.desc),
                        "address transactions");
                for (AddressTransactionContent tx : txs) {
                    int confirmations = confirmations(latest, tx.getBlockHeight());
                    TxContentUtxo utxo = CardanoBackendClient.requireSuccess(
                            backend.getTransactionService().getTransactionUtxos(tx.getTxHash()),
                            "transaction utxos");
                    scanOutputs(address, utxo, tokensByUnit, managedAddresses,
                            tx, confirmations, requiredConfirmations, events);
                }
            }
            long safeHeight = Math.max(0L, latest - requiredConfirmations + 1L);
            repository.updateScanHeight(CHAIN, SCANNER, latest, safeHeight);
            return events;
        });
    }

    private void scanOutputs(TrackedCardanoAddress tracked, TxContentUtxo utxo,
                             Map<String, TokenDefinition> tokensByUnit,
                             Set<String> managedAddresses,
                             AddressTransactionContent tx, int confirmations, int requiredConfirmations,
                             List<DepositEvent> events) {
        String fromAddress = firstInputAddress(utxo);
        Set<String> inputAddresses = inputAddresses(utxo);
        boolean managedInput = inputAddresses.stream().anyMatch(managedAddresses::contains);
        for (TxContentUtxoOutputs output : safeOutputs(utxo)) {
            if (!tracked.normalizedAddress().equals(normalize(output.getAddress()))) {
                continue;
            }
            if (inputAddresses.contains(tracked.normalizedAddress())) {
                continue;
            }
            int assetIndex = 0;
            for (TxContentOutputAmount amount : safeAmounts(output.getAmount())) {
                String unit = CardanoAssetUnit.normalize(amount.getUnit());
                BigInteger atomic = new BigInteger(amount.getQuantity());
                if (atomic.signum() <= 0) {
                    assetIndex++;
                    continue;
                }
                CreditableDeposit deposit = depositForAmount(unit, atomic, tokensByUnit, tx, tracked,
                        output.getOutputIndex(), assetIndex, confirmations, fromAddress, output);
                if (deposit == null) {
                    assetIndex++;
                    continue;
                }
                if (managedInput && reservedHotAddress(deposit.addressRecord())) {
                    assetIndex++;
                    continue;
                }
                repository.recordAndCreditDeposit(deposit.event(),
                        CardanoAssetUnit.depositLogIndex(output.getOutputIndex(), assetIndex),
                        requiredConfirmations, deposit.addressRecord().getAccountId());
                events.add(deposit.event());
                assetIndex++;
            }
        }
    }

    private CreditableDeposit depositForAmount(String unit, BigInteger atomic,
                                               Map<String, TokenDefinition> tokensByUnit,
                                               AddressTransactionContent tx, TrackedCardanoAddress tracked,
                                               int outputIndex, int assetIndex, int confirmations,
                                               String fromAddress, TxContentUtxoOutputs output) {
        if (CardanoAssetUnit.LOVELACE.equals(unit)) {
            ChainAddressRecord addressRecord = tracked.nativeRecord();
            if (addressRecord == null) {
                return null;
            }
            BigDecimal amount = new BigDecimal(atomic).movePointLeft(ADA_DECIMALS).stripTrailingZeros();
            DepositEvent event = new DepositEvent(ChainType.ADA, SYMBOL, tx.getTxHash(), fromAddress,
                    addressRecord.getAddress(), amount, tx.getBlockHeight(), tx.getTxHash(), confirmations,
                    null, output.toString());
            return new CreditableDeposit(event, addressRecord);
        }
        TokenDefinition token = tokensByUnit.get(unit);
        if (token == null) {
            return null;
        }
        ChainAddressRecord addressRecord = tracked.tokenRecordsByUnit().get(unit);
        if (addressRecord == null) {
            return null;
        }
        BigDecimal amount = new BigDecimal(atomic).movePointLeft(token.getDecimals()).stripTrailingZeros();
        DepositEvent event = new DepositEvent(ChainType.ADA, token.getSymbol(), tx.getTxHash(), fromAddress,
                addressRecord.getAddress(), amount, tx.getBlockHeight(), tx.getTxHash(), confirmations,
                unit, output.toString());
        return new CreditableDeposit(event, addressRecord);
    }
    Map<String, TrackedCardanoAddress> trackedDepositAddresses(Map<String, TokenDefinition> tokensByUnit) {
        Map<String, MutableTrackedCardanoAddress> addresses = new HashMap<>();
        for (ChainAddressRecord address : repository.listChainAddresses(CHAIN, SYMBOL)) {
            if ("DEPOSIT".equals(address.getWalletRole())) {
                String normalizedAddress = normalize(address.getAddress());
                addresses.computeIfAbsent(normalizedAddress, key -> new MutableTrackedCardanoAddress(
                        normalizedAddress, address.getAddress())).nativeRecord = address;
            }
        }
        for (TokenDefinition token : tokensByUnit.values()) {
            String unit = CardanoAssetUnit.fromTokenContract(token.getContractAddress());
            for (ChainAddressRecord address : repository.listChainAddresses(CHAIN, token.getSymbol())) {
                if ("DEPOSIT".equals(address.getWalletRole())) {
                    String normalizedAddress = normalize(address.getAddress());
                    addresses.computeIfAbsent(normalizedAddress, key -> new MutableTrackedCardanoAddress(
                            normalizedAddress, address.getAddress())).tokenRecordsByUnit.put(unit, address);
                }
            }
        }
        Map<String, TrackedCardanoAddress> immutableAddresses = new HashMap<>();
        addresses.forEach((key, value) -> immutableAddresses.put(key, value.toRecord()));
        return immutableAddresses;
    }
    private Map<String, TokenDefinition> tokensByUnit() {
        Map<String, TokenDefinition> tokens = new HashMap<>();
        for (TokenDefinition token : repository.listTokens(CHAIN)) {
            if (Boolean.TRUE.equals(token.getActive()) && token.getContractAddress() != null) {
                tokens.put(CardanoAssetUnit.fromTokenContract(token.getContractAddress()), token);
            }
        }
        return tokens;
    }
    private static List<TxContentUtxoOutputs> safeOutputs(TxContentUtxo utxo) {
        return utxo == null || utxo.getOutputs() == null ? List.of() : utxo.getOutputs();
    }
    private static List<TxContentOutputAmount> safeAmounts(List<TxContentOutputAmount> amounts) {
        return amounts == null ? List.of() : amounts;
    }
    private static String firstInputAddress(TxContentUtxo utxo) {
        if (utxo == null || utxo.getInputs() == null) {
            return "";
        }
        for (TxContentUtxoInputs input : utxo.getInputs()) {
            if (input.getAddress() != null && !input.getAddress().isBlank()) {
                return input.getAddress();
            }
        }
        return "";
    }
    private static Set<String> inputAddresses(TxContentUtxo utxo) {
        if (utxo == null || utxo.getInputs() == null) {
            return Set.of();
        }
        return utxo.getInputs().stream()
                .map(TxContentUtxoInputs::getAddress)
                .map(CardanoDepositScanner::normalize)
                .filter(address -> !address.isBlank())
                .collect(Collectors.toSet());
    }
    private static boolean reservedHotAddress(ChainAddressRecord address) {
        return address.getUserId() == 0L && address.getBiz() == 0 && address.getAddressIndex() == 0L;
    }
    private static int confirmations(long latest, long blockHeight) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, latest - blockHeight + 1L));
    }
    private static int requiredConfirmations(AccountChainProfile profile) {
        Integer configured = profile.getDepositConfirmations();
        return configured == null || configured <= 0 ? 15 : configured;
    }
    private static int scanLimit(AccountChainProfile profile) {
        Integer batchSize = profile.getScanBatchSize();
        return batchSize == null || batchSize <= 0 ? 50 : Math.min(batchSize, 100);
    }
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    record TrackedCardanoAddress(String normalizedAddress, String address, ChainAddressRecord nativeRecord,
                                 Map<String, ChainAddressRecord> tokenRecordsByUnit) {
    }
    private record CreditableDeposit(DepositEvent event, ChainAddressRecord addressRecord) {
    }
    private static final class MutableTrackedCardanoAddress {
        private final String normalizedAddress;
        private final String address;
        private ChainAddressRecord nativeRecord;
        private final Map<String, ChainAddressRecord> tokenRecordsByUnit = new HashMap<>();

        private MutableTrackedCardanoAddress(String normalizedAddress, String address) {
            this.normalizedAddress = normalizedAddress;
            this.address = address;
        }

        private TrackedCardanoAddress toRecord() {
            return new TrackedCardanoAddress(normalizedAddress, address, nativeRecord, Map.copyOf(tokenRecordsByUnit));
        }
    }
    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }
}
