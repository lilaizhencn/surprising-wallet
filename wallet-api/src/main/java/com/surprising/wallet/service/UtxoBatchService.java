package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import com.surprising.wallet.repository.WalletOutboxRepository;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

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
    /** 统一数据库访问，包含订单、UTXO、签名交易等表。 */
    private final ChainJdbcRepository chainJdbcRepository;
    /** 提现任务开关服务。 */
    private final WalletRuntimeConfigService runtimeConfigService;
    /** PostgreSQL 网络费率报价。 */
    private final ChainFeeRateService feeRates;
    /** 签名 Outbox 仓储，保证数据库提交后任务不会丢失。 */
    private final WalletOutboxRepository outbox;
    /** Jackson 3 对象映射器，用于构建和序列化签名队列 JSON。 */
    private final ObjectMapper objectMapper;

    /** 单签链标记集合，目前默认空。 */
    private static final Set<AssetRuntimeMetadata> SINGLE_SIG_CURRENCY = Collections.emptySet();
    /** 构造 UTXO 批处理服务。 */
    public UtxoBatchService(
            BlockchainRuntimeService blockchainRuntimeService,
            ChainJdbcRepository chainJdbcRepository,
            WalletRuntimeConfigService runtimeConfigService,
            ChainFeeRateService feeRates,
            ObjectMapper objectMapper,
            WalletOutboxRepository outbox) {
        this.blockchainRuntimeService = blockchainRuntimeService;
        this.chainJdbcRepository = chainJdbcRepository;
        this.runtimeConfigService = runtimeConfigService;
        this.feeRates = feeRates;
        this.objectMapper = objectMapper;
        this.outbox = outbox;
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
                        chainJdbcRepository.listWithdrawalsForSigning(chain, chain, COUNT);
                if (queuedOrders == null || queuedOrders.isEmpty()) {
                    break;
                }
                UUID tenantId = Objects.requireNonNull(
                        queuedOrders.getFirst().getTenantId(), "withdrawal tenantId is required");
                List<WithdrawalOrderRecord> orders = queuedOrders.stream()
                        .filter(order -> tenantId.equals(order.getTenantId()))
                        .toList();
                List<WithdrawRecord> records = orders.stream()
                        .map(order -> toWithdrawRecord(order, currency))
                        .toList();
                WithdrawTransaction transaction = buildTransaction(tenantId, records, currency);
                if (transaction == null) {
                    log.error("UTXO批处理异常 交易创建失败 币种:{}", currency.getName());
                    break;
                }
                enqueueSigning(tenantId, currency, transaction);

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
        String configured = feeRates.get(chain);
        int feeRate = configured == null || Integer.parseInt(configured) <= 0
                ? defaultFeeRate(chain) : Integer.parseInt(configured);
        long confirmations = blockchainRuntimeService.depositConfirmationThreshold(currency);
        long dust = blockchainRuntimeService.dustThresholdAtomic(currency);
        List<CollectionCandidateRecord> candidates = chainJdbcRepository.listCollectableLedgerBalances(
                chain, BigDecimal.ZERO, COLLECTION_LIMIT);
        for (CollectionCandidateRecord candidate : candidates) {
            UUID tenantId = candidate.getTenantId();
            String source = candidate.getAddress();
            String target = chainJdbcRepository.findActiveTenantCollectionAddress(tenantId, chain)
                    .orElseThrow(() -> new IllegalStateException("BTC collection address is missing"));
            if (source.equalsIgnoreCase(target)) {
                continue;
            }
            List<UtxoTransaction> utxos = chainJdbcRepository.listCreditedSpendableUtxosAtAddress(
                    tenantId, chain, chain, source, confirmations, COLLECTION_INPUT_LIMIT);
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
            if (chainJdbcRepository.createCollectionRecord(tenantId, candidate.getCustodyAddressId(),
                    collectionNo, chain, chain, source, target, amount, fee, null) != 1) {
                continue;
            }
            WithdrawRecord output = WithdrawRecord.builder()
                    .withdrawId(collectionNo).userId(candidate.getUserId())
                    .currency(currency.getIndex()).address(target).balance(amount)
                    .fee(BigDecimal.ZERO).status((byte) Constants.WAITING)
                    .createDate(Date.from(Instant.now())).updateDate(Date.from(Instant.now())).build();
            List<Address> addresses = utxos.stream().map(utxo -> chainJdbcRepository
                    .findChainAddressByAddress(tenantId, chain, utxo.getAddress())
                    .map(address -> toAddress(address, currency))
                    .orElseThrow(() -> new IllegalStateException("BTC collection input address is missing")))
                    .toList();
            ObjectNode signature = objectMapper.createObjectNode();
            signature.put("tenantId", tenantId.toString());
            signature.put("signingRequestId", UUID.randomUUID().toString());
            signature.put("operationType", "COLLECTION");
            signature.put("collectionNo", collectionNo);
            signature.set("utxos", objectMapper.valueToTree(utxos));
            signature.set("addresses", objectMapper.valueToTree(addresses));
            signature.set("withdraw", objectMapper.valueToTree(List.of(output)));
            signature.put("changeAddress", target);
            signature.put("feeRate", feeRate);
            signature.put("totalAmount", input.toPlainString());
            if (dust > 0) {
                signature.put("dustThreshold", dust);
            }
            WithdrawTransaction transaction = WithdrawTransaction.builder()
                    .balance(input).currency(currency.getIndex()).status(Constants.SIGNING)
                    .txId("signing").signature(JacksonJson.writeValue(objectMapper, signature)).build();
            currency.applyTo(transaction);
            transaction = chainJdbcRepository.createBitcoinLikeSigningTransaction(
                    currency, "COLLECTION", collectionNo, transaction);
            for (UtxoTransaction utxo : utxos) {
                if (chainJdbcRepository.lockUtxo(tenantId, chain, utxo.getTxId(), utxo.getSeq(),
                        transaction.getId().toString()) != 1) {
                    throw new IllegalStateException("BTC collection UTXO lock failed");
                }
            }
            if (chainJdbcRepository.claimCollectionSigning(tenantId, chain, collectionNo, null) != 1) {
                throw new IllegalStateException("BTC collection claim failed");
            }
            enqueueSigning(tenantId, currency, transaction);
        }
    }

    private void enqueueSigning(UUID tenantId, AssetRuntimeMetadata currency, WithdrawTransaction transaction) {
        String topic = SINGLE_SIG_CURRENCY.contains(currency)
                ? WalletOutboxDispatchService.SIGNING_SECOND_TOPIC
                : WalletOutboxDispatchService.SIGNING_FIRST_TOPIC;
        int persisted = outbox.insert(UUID.randomUUID(), tenantId, topic, "CHAIN_SIGNING_TRANSACTION",
                transaction.getId().toString(), transaction.getId().toString(),
                JacksonJson.writeValue(objectMapper, transaction));
        log.info("UTXO signing task persisted={} topic={} id={}", persisted, topic, transaction.getId());
    }

    /**
     * 根据一批提现记录构建签名交易：
     * 1) 计算总金额与找零需求；2) 拉取足量 UTXO；3) 重组输出与签名数据；
     * 4) 入签名表；5) 锁定 UTXO 与更新订单状态。
     */
    private WithdrawTransaction buildTransaction(
            UUID tenantId, List<WithdrawRecord> records, AssetRuntimeMetadata currency) {
        log.info("构建提现交易对象开始");
        int size = 1;
        WithdrawTransaction transaction = null;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal withdrawAmount = BigDecimal.ZERO;
        for (WithdrawRecord record : records) {
            totalAmount = totalAmount.add(record.getBalance()).add(record.getFee());
            withdrawAmount = withdrawAmount.add(record.getBalance());
        }
        String configuredFeeRateValue = feeRates.get(currency.getName());
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
        List<Address> addresses = new LinkedList<>();
        utxos = new LinkedList<>();
        walletAmount = BigDecimal.ZERO;

        while (descendingIterator.hasNext()) {
            UtxoTransaction utxo = descendingIterator.next();
            utxos.add(utxo);
            walletAmount = walletAmount.add(utxo.getBalance());
            Address address = chainJdbcRepository.findChainAddressByAddress(
                            tenantId, chain, utxo.getAddress())
                    .map(record -> toAddress(record, currency))
                    .orElseThrow(() -> new IllegalStateException(
                            "missing chain_address for " + chain + " UTXO address " + utxo.getAddress()));
            addresses.add(address);
            if (walletAmount.compareTo(requiredAmount(
                    totalAmount, withdrawAmount, utxos.size(), records.size(), feeRate, currency)) > 0) {
                break;
            }
        }

        // 初始化待签名 payload
        ObjectNode signature = objectMapper.createObjectNode();
        signature.put("tenantId", tenantId.toString());
        signature.put("signingRequestId", UUID.randomUUID().toString());
        Address changeAddress = defaultHotChangeAddress(tenantId, currency);
        signature.set("utxos", objectMapper.valueToTree(utxos));
        signature.set("addresses", objectMapper.valueToTree(addresses));
        signature.set("withdraw", objectMapper.valueToTree(records));
        signature.put("changeAddress", changeAddress.getAddress());
        signature.put("feeRate", feeRate);
        signature.put("totalAmount", totalAmount.toPlainString());
        long dustThreshold = blockchainRuntimeService.dustThresholdAtomic(currency);
        if (dustThreshold > 0) {
            signature.put("dustThreshold", dustThreshold);
        }

        transaction = WithdrawTransaction.builder()
                .balance(walletAmount)
                .currency(currency.getIndex())
                .status(Constants.SIGNING)
                .txId("signing")
                .signature(JacksonJson.writeValue(objectMapper, signature))
                .build();
        currency.applyTo(transaction);
        String businessNo = records.stream()
                .map(WithdrawRecord::getWithdrawId)
                .filter(Objects::nonNull)
                .sorted()
                .reduce((left, right) -> left + "|" + right)
                .orElseThrow(() -> new IllegalStateException("withdraw batch has no withdrawId"));
        transaction = chainJdbcRepository.createBitcoinLikeSigningTransaction(
                currency, "WITHDRAW", businessNo, transaction);

        String transactionId = transaction.getId().toString();

        // 锁定本次交易所有输入 UTXO，避免双花
        for (UtxoTransaction utxo : utxos) {
            int locked = chainJdbcRepository.lockUtxo(
                    tenantId, chain, utxo.getTxId(), utxo.getSeq(), transactionId);
            if (locked != 1) {
                throw new IllegalStateException(
                        "failed to lock unified " + chain + " UTXO "
                                + utxo.getTxId() + ":" + utxo.getSeq());
            }
        }

        // 更新提现订单状态：先锁定再进入签名态
        String fromAddress = addresses.isEmpty() ? null : addresses.get(0).getAddress();
        records.forEach(record -> {
            chainJdbcRepository.updateWithdrawalStatus(
                    tenantId, chain, record.getWithdrawId(), "UTXO_LOCKED", fromAddress, null, null);
            chainJdbcRepository.updateWithdrawalStatus(
                    tenantId, chain, record.getWithdrawId(), "SIGNING", fromAddress, null, null);
        });

        // 回填内存对象用于本次批次后续流程透传
        records.parallelStream().forEach((record) -> {
            record.setStatus((byte) Constants.SIGNING);
            record.setTxId(transactionId);
            record.setUpdateDate(Date.from(Instant.now()));
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
        return chainJdbcRepository.listSpendableUtxos(
                tenantId, chain, chain, depositConfirmationThreshold, limit, offset);
    }

    /**
     * 查询租户可用热钱包地址，作为本次构建交易找零输出地址。
     */
    private Address defaultHotChangeAddress(UUID tenantId, AssetRuntimeMetadata currency) {
        return chainJdbcRepository.findActiveTenantCollectionAddress(tenantId, currency.chain())
                .flatMap(address -> chainJdbcRepository.findChainAddressByAddress(
                        tenantId, currency.chain(), currency.assetSymbol(), address))
                .map(record -> toAddress(record, currency))
                .orElseThrow(() -> new IllegalStateException(
                        "missing tenant hot wallet change address for "
                                + currency.chain() + "/" + currency.assetSymbol()));
    }

    /**
     * 将 DB 订单对象转换为统一提现记录，后续统一下发签名队列。
     */
    private WithdrawRecord toWithdrawRecord(WithdrawalOrderRecord order, AssetRuntimeMetadata currency) {
        return WithdrawRecord.builder()
                .withdrawId(order.getOrderNo())
                .userId(order.getUserId())
                .currency(currency.getIndex())
                .address(order.getToAddress())
                .balance(order.getAmount())
                .fee(order.getFee() == null ? BigDecimal.ZERO : order.getFee())
                .status((byte) Constants.WAITING)
                .createDate(toDate(order.getCreatedAt()))
                .updateDate(toDate(order.getUpdatedAt()))
                .build();
    }

    /**
     * 构建签名引擎可识别的地址对象。
     */
    private Address toAddress(ChainAddressRecord record, AssetRuntimeMetadata currency) {
        return Address.builder()
                .userId(record.getUserId())
                .address(record.getAddress())
                .currency(currency.getName())
                .biz(record.getBiz())
                .index(Math.toIntExact(record.getAddressIndex()))
                .derivationPath(record.getDerivationPath())
                .scriptType("P2WSH")
                .build();
    }

    /**
     * 安全转换 instant，避免空值导致 NPE。
     */
    private Date toDate(Instant instant) {
        return instant == null ? Date.from(Instant.now()) : Date.from(instant);
    }
}
