package com.surprising.wallet.service;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.chain.model.UtxoSigningTransaction;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.ChainFeeRateRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainSigningTransactionRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import java.math.BigDecimal;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UTXO 链批处理服务（提现 + 归集）。
 * <p>
 * UTXO 模型下，提现和归集天然同属一笔多输出交易——本类从 DB 拉取该链所有待签名
 * withdrawal_order（不分提现/归集），选取可用 UTXO，构建一笔批量交易推送到 PostgreSQL / PGMQ
 * 签名队列，由 sig1/sig2 依次签名后广播上链。
 */
@Slf4j
@Service
public class UtxoBatchService {
    /** 每批次最多处理 10 笔订单。 */
    private static final int COUNT = 10;
    private static final int COLLECTION_LIMIT = 20;
    private static final int COLLECTION_INPUT_LIMIT = 50;
    /** 链元数据服务。 */
    private final BlockchainRuntimeService blockchainRuntimeService;
    /** 提现订单与归集规划分别由对应仓储和用例负责。 */
    private final WithdrawalOrderRepository withdrawalOrderRepository;
    private final CollectionPlanningService collectionPlanningService;
    private final UtxoSelectionService utxoSelectionService;
    private final CollectionRecordRepository collectionRecordRepository;
    private final ChainAddressRepository chainAddressRepository;

    /** 提现任务开关服务。 */
    private final WalletRuntimeConfigService runtimeConfigService;
    /** PostgreSQL 网络费率报价。 */
    private final ChainFeeRateRepository feeRates;
    private final UtxoSigningSubmissionService signingSubmissions;
    private final ChainSigningTransactionRepository signingTransactions;
    private final ChainProfileRepository profiles;

    /** 构造 UTXO 批处理服务。 */
    public UtxoBatchService(
            BlockchainRuntimeService blockchainRuntimeService,
            WithdrawalOrderRepository withdrawalOrderRepository,
            CollectionPlanningService collectionPlanningService,
            UtxoSelectionService utxoSelectionService,
            CollectionRecordRepository collectionRecordRepository,
            ChainAddressRepository chainAddressRepository,
            WalletRuntimeConfigService runtimeConfigService,
            ChainFeeRateRepository feeRates,
            UtxoSigningSubmissionService signingSubmissions,
            ChainSigningTransactionRepository signingTransactions,
            ChainProfileRepository profiles) {
        this.blockchainRuntimeService = blockchainRuntimeService;
        this.withdrawalOrderRepository = withdrawalOrderRepository;
        this.collectionPlanningService = collectionPlanningService;
        this.utxoSelectionService = utxoSelectionService;
        this.collectionRecordRepository = collectionRecordRepository;
        this.chainAddressRepository = chainAddressRepository;
        this.runtimeConfigService = runtimeConfigService;
        this.feeRates = feeRates;
        this.signingTransactions = signingTransactions;
        this.profiles = profiles;
        this.signingSubmissions = signingSubmissions;
    }

    /**
     * 执行批处理主流程：拉取待签名订单、构建交易、入库签名交易并推送到签名队列。
     */
    @Transactional(rollbackFor = Throwable.class)
    public void execute(String chain) {
        boolean withdrawEnabled = runtimeConfigService.isTaskEnabled(
                chain, WalletRuntimeConfigService.TASK_WITHDRAW);
        boolean collectionEnabled = "BTC".equalsIgnoreCase(chain)
                && runtimeConfigService.isTaskEnabled(chain, WalletRuntimeConfigService.TASK_COLLECTION);
        if (!withdrawEnabled && !collectionEnabled) {
            return;
        }
        AssetRuntimeMetadata currency = blockchainRuntimeService.assetMetadata(chain);
        log.info("UTXO批处理（提现+归集）开始 币种:{}", currency.getName());

        try {
            while (withdrawEnabled) {
                List<WithdrawalOrderRecord> queuedOrders =
                        withdrawalOrderRepository.listForSigning(chain, chain, COUNT);
                if (queuedOrders == null || queuedOrders.isEmpty()) {
                    break;
                }
                UUID tenantId = Objects.requireNonNull(
                        queuedOrders.getFirst().getTenantId(), "withdrawal tenantId is required");
                List<WithdrawalOrderRecord> orders = queuedOrders.stream()
                        .filter(order -> tenantId.equals(order.getTenantId()))
                        .toList();
                List<UtxoSigningRequest.Payment> records = orders.stream()
                        .map(order -> new UtxoSigningRequest.Payment(order.getOrderNo(), order.getUserId(),
                                order.getToAddress(), order.getAmount(), order.getFee()))
                        .toList();
                UtxoSigningTransaction transaction = buildTransaction(tenantId, records, currency);
                if (transaction == null) {
                    log.error("UTXO批处理异常 交易创建失败 币种:{}", currency.getName());
                    break;
                }
                signingSubmissions.enqueue(tenantId, currency.chain(), transaction.id(), transaction.request().requestId());

                // 说明数据库中没有等待签名的交易了，不需要继续循环
                if (queuedOrders.size() < COUNT) {
                    break;
                }
            }
            if (collectionEnabled) {
                collectBtc(currency);
            }
        } catch (Throwable e) {
            log.error("UTXO批处理扫描数据,构建交易,发送到pgmq队列出现异常 币种id:{}", currency.getName(), e);
            throw new IllegalStateException("UTXO batch transaction rolled back", e);
        }

        log.info("UTXO批处理结束 币种:{}", currency.getName());
    }

    /** Sweep confirmed deposit outputs to the tenant's internal collection address. */
    private void collectBtc(AssetRuntimeMetadata currency) {
        String chain = currency.chain().toUpperCase(Locale.ROOT);
        String configured = feeRates.find(chain);
        int feeRate = configured == null || Integer.parseInt(configured) <= 0
                ? defaultFeeRate(chain) : Integer.parseInt(configured);
        long confirmations = blockchainRuntimeService.depositConfirmationThreshold(currency);
        long dust = blockchainRuntimeService.dustThresholdAtomic(currency);
        List<CollectionCandidateRecord> candidates = collectionPlanningService.candidates(chain, BigDecimal.ZERO, COLLECTION_LIMIT);
        for (CollectionCandidateRecord candidate : candidates) {
            UUID tenantId = candidate.getTenantId();
            String source = candidate.getAddress();
            String target = collectionPlanningService.destination(tenantId, chain)
                    .orElseThrow(() -> new IllegalStateException("BTC collection address is missing"));
            if (source.equalsIgnoreCase(target)) {
                continue;
            }
            List<UtxoTransaction> utxos = utxoSelectionService.creditedAtAddress(
                    tenantId,
                    chain,
                    chain,
                    source,
                    confirmations,
                    COLLECTION_INPUT_LIMIT);
            if (utxos.isEmpty()) {
                continue;
            }
            BigDecimal input = utxos.stream().map(UtxoTransaction::getBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long feeAtomic = estimateNetworkFeeAtomic(chain, utxos.size(), 1, feeRate);
            BigDecimal fee = BigDecimal.valueOf(feeAtomic).divide(currency.getDecimal());
            BigDecimal amount = input.subtract(fee);
            if (amount.signum() <= 0 || amount.multiply(currency.getDecimal()).longValueExact() < dust) {
                continue;
            }
            String collectionNo = "COLL-BTC-" + UUID.randomUUID();
            if (collectionRecordRepository.create(
                    tenantId,
                    candidate.getCustodyAddressId(),
                    collectionNo,
                    chain,
                    chain,
                    source,
                    target,
                    amount,
                    fee,
                    null) != 1) {
                continue;
            }
            var output = new UtxoSigningRequest.Payment(collectionNo, candidate.getUserId(), target, amount, BigDecimal.ZERO);
            var inputs = utxos.stream().map(utxo -> signingInput(tenantId, chain, utxo)).toList();
            var request = new UtxoSigningRequest(tenantId, UUID.randomUUID(), signingAsset(currency),
                    UtxoSigningRequest.Operation.COLLECTION, collectionNo, inputs, List.of(output), target, feeRate, dust);
            UtxoSigningTransaction transaction = signingTransactions.create(request, collectionNo);
            for (UtxoTransaction utxo : utxos) {
                if (utxoSelectionService.lock(tenantId, chain, utxo.getTxId(), utxo.getSeq(), Integer.toString(transaction.id())) != 1) {
                    throw new IllegalStateException("BTC collection UTXO lock failed");
                }
            }
            if (collectionRecordRepository.claimSigning(tenantId, chain, collectionNo, null) != 1) {
                throw new IllegalStateException("BTC collection claim failed");
            }
            signingSubmissions.enqueue(tenantId, chain, transaction.id(), transaction.request().requestId());
        }
    }

    /**
     * 根据一批提现记录构建签名交易：
     * 1) 计算总金额与找零需求；2) 拉取足量 UTXO；3) 重组输出与签名数据；
     * 4) 入签名表；5) 锁定 UTXO 与更新订单状态。
     */
    private UtxoSigningTransaction buildTransaction(
            UUID tenantId, List<UtxoSigningRequest.Payment> records, AssetRuntimeMetadata currency) {
        log.info("构建提现交易对象开始");
        int size = 1;
        UtxoSigningTransaction transaction = null;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal withdrawAmount = BigDecimal.ZERO;
        for (var record : records) {
            totalAmount = totalAmount.add(record.amount()).add(record.customerFee());
            withdrawAmount = withdrawAmount.add(record.amount());
        }
        String configuredFeeRateValue = feeRates.find(currency.getName());
        Integer configuredFeeRate = configuredFeeRateValue == null ? null : Integer.valueOf(configuredFeeRateValue);
        String chain = currency.getName().toUpperCase(Locale.ROOT);
        int feeRate = configuredFeeRate == null || configuredFeeRate <= 0
                ? defaultFeeRate(chain) : configuredFeeRate;
        long depositConfirmationThreshold = blockchainRuntimeService.depositConfirmationThreshold(currency);
        int offset = 0;

        // 逐步按分页方式选取可花费 UTXO，直到余额足够覆盖本批提现+费用
        LinkedList<UtxoTransaction> utxos = new LinkedList<>();
        BigDecimal walletAmount = BigDecimal.ZERO;
        while (true) {
            List<UtxoTransaction> tmps = listCandidateUtxos(
                    tenantId, depositConfirmationThreshold, size, offset, currency);
            if (tmps == null || tmps.isEmpty()) {
                log.error("构建交易失败 钱包余额不足");
                return null;
            }
            utxos.addAll(tmps);
            // 因为 page size 为 1，所以查询结果中只有一条数据
            UtxoTransaction utxo = tmps.get(0);
            walletAmount = walletAmount.add(utxo.getBalance());
            if (walletAmount.compareTo(requiredAmount(
                    totalAmount, withdrawAmount, utxos.size(), records.size(), feeRate, currency)) > 0) {
                break;
            }
            offset += size;
        }

        // 反向过滤：优先使用金额较大的 UTXO，尽量减少输入数量
        Iterator<UtxoTransaction> descendingIterator = utxos.descendingIterator();
        var inputs = new ArrayList<UtxoSigningRequest.Input>();
        utxos = new LinkedList<>();
        walletAmount = BigDecimal.ZERO;

        while (descendingIterator.hasNext()) {
            UtxoTransaction utxo = descendingIterator.next();
            utxos.add(utxo);
            walletAmount = walletAmount.add(utxo.getBalance());
            inputs.add(signingInput(tenantId, chain, utxo));
            if (walletAmount.compareTo(requiredAmount(
                    totalAmount, withdrawAmount, utxos.size(), records.size(), feeRate, currency)) > 0) {
                break;
            }
        }

        var request = new UtxoSigningRequest(tenantId, UUID.randomUUID(), signingAsset(currency),
                UtxoSigningRequest.Operation.WITHDRAWAL, null, inputs, records,
                defaultHotChangeAddress(tenantId, currency), feeRate, blockchainRuntimeService.dustThresholdAtomic(currency));
        String businessNo = records.stream().map(UtxoSigningRequest.Payment::reference).sorted()
                .reduce((left, right) -> left + "|" + right).orElseThrow();
        transaction = signingTransactions.create(request, businessNo);

        String transactionId = Integer.toString(transaction.id());

        // 锁定本次交易所有输入 UTXO，避免双花
        for (UtxoTransaction utxo : utxos) {
            int locked = utxoSelectionService.lock(tenantId, chain, utxo.getTxId(), utxo.getSeq(), transactionId);
            if (locked != 1) {
                throw new IllegalStateException(
                        "failed to lock unified " + chain + " UTXO "
                                + utxo.getTxId() + ":" + utxo.getSeq());
            }
        }

        // 更新提现订单状态：先锁定再进入签名态
        String fromAddress = inputs.getFirst().address();
        records.forEach(record -> {
            withdrawalOrderRepository.updateStatus(tenantId, chain, record.reference(), "UTXO_LOCKED", fromAddress, null, null);
            withdrawalOrderRepository.updateStatus(tenantId, chain, record.reference(), "SIGNING", fromAddress, null, null);
        });

        log.info("交易创建完成");
        return transaction;
    }

    /**
     * 计算本次签名交易最小必要总额（用户金额 + network fee）。
     */
    private BigDecimal requiredAmount(
            BigDecimal userFeeRequired,
            BigDecimal withdrawAmount,
            int inputCount,
            int outputCount,
            int feeRate,
            AssetRuntimeMetadata currency) {
        long feeSat = estimateNetworkFeeAtomic(
                currency.getName().toUpperCase(Locale.ROOT),
                Math.max(inputCount, 1), outputCount + 1, feeRate);
        BigDecimal networkFee = BigDecimal.valueOf(feeSat).divide(currency.getDecimal());
        BigDecimal dynamicRequired = withdrawAmount.add(networkFee);
        return dynamicRequired.max(userFeeRequired);
    }

    /** 根据链类型返回未配置时的默认费率。 */
    private int defaultFeeRate(String chain) {
        return switch (chain) {
            case "BCH" -> (int) BitcoinCashFeePolicy.DEFAULT_SAT_PER_BYTE;
            case "DOGE" -> (int) DogecoinFeePolicy.DEFAULT_FEE_RATE_KOINU_PER_BYTE;
            case "LTC" -> (int) LitecoinFeePolicy.DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE;
            default -> 10;
        };
    }

    /** 根据链类型估算网络手续费。 */
    private long estimateNetworkFeeAtomic(String chain, int inputCount, int outputCount, int feeRate) {
        return switch (chain) {
            case "BCH" -> P2shMultisigFeeCalculator.estimateBytes(
                    inputCount, outputCount, 2, 3) * feeRate;
            case "DOGE" -> DogecoinFeePolicy.feeForBytes(
                    P2shMultisigFeeCalculator.estimateBytes(inputCount, outputCount, 2, 3), feeRate);
            default -> P2wshFeeCalculator.calculateFeeSat(inputCount, outputCount, feeRate);
        };
    }

    /**
     * 按分页从 DB 拉取可用 UTXO。
     */
    private List<UtxoTransaction> listCandidateUtxos(
            UUID tenantId,
            long depositConfirmationThreshold,
            int limit,
            int offset,
            AssetRuntimeMetadata currency) {
        String chain = currency.getName().toUpperCase(Locale.ROOT);
        return utxoSelectionService.spendable(tenantId, chain, chain, depositConfirmationThreshold, limit, offset);
    }

    /**
     * 查询租户可用热钱包地址，作为本次构建交易找零输出地址。
     */
    private String defaultHotChangeAddress(UUID tenantId, AssetRuntimeMetadata currency) {
        return collectionPlanningService.destination(tenantId, currency.chain())
                .flatMap(address -> chainAddressRepository.findEnabledByTenantAndAddress(
                        tenantId,
                        currency.chain(),
                        currency.assetSymbol(),
                        address))
                .map(ChainAddressRecord::getAddress)
                .orElseThrow(() -> new IllegalStateException("missing tenant change address for " + currency.chain()));
    }

    private UtxoSigningRequest.Input signingInput(UUID tenantId, String chain, UtxoTransaction utxo) {
        var address = chainAddressRepository.findEnabledByTenantAndAddress(tenantId, chain, utxo.getAddress())
                .orElseThrow(() -> new IllegalStateException("missing input address for " + chain));
        var key = new UtxoSigningRequest.Key(address.getUserId(), address.getBiz(),
                Math.toIntExact(address.getAddressIndex()), null);
        return new UtxoSigningRequest.Input(utxo.getTxId(), utxo.getSeq(), utxo.getAddress(), utxo.getBalance(), key);
    }

    private UtxoSigningRequest.Asset signingAsset(AssetRuntimeMetadata currency) {
        var profile = profiles.findAccountByChain(currency.chain()).orElseThrow();
        return UtxoSigningRequest.Asset.from(currency, profile.getNetwork());
    }
}
