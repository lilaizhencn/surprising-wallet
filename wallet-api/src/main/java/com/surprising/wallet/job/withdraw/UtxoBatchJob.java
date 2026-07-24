package com.surprising.wallet.job.withdraw;

import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.service.chain.BlockchainRuntimeService;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * UTXO 链批处理基类（提现 + 归集）。
 * <p>
 * UTXO 模型下，提现和归集天然同属一笔多输出交易——本类从 DB 拉取该链所有待签名
 * withdrawal_order（不分提现/归集），选取可用 UTXO，构建一笔批量交易推送到 Redis
 * 签名队列，由 sig1/sig2 依次签名后广播上链。
 */
@Slf4j
abstract public class UtxoBatchJob {
    /** 每批次最多处理 10 笔订单。 */
    private final int COUNT = 10;
    /** 当前链资产元数据，构建交易前按链动态加载。 */
    public AssetRuntimeMetadata currency;
    /** 链元数据服务。 */
    @Autowired
    BlockchainRuntimeService blockchainRuntimeService;
    /** 统一数据库访问，包含订单、UTXO、签名交易等表。 */
    @Autowired
    ChainJdbcRepository chainJdbcRepository;
    /** 提现任务开关服务。 */
    @Autowired
    protected WalletRuntimeConfigService runtimeConfigService;

    /** 单签链标记集合，目前默认空。 */
    private static final Set<AssetRuntimeMetadata> SINGLE_SIG_CURRENCY = Collections.emptySet();
    /** 未配置 fee-rate 时的兜底费率。 */
    private static final int DEFAULT_FEE_RATE = 10;

    /**
     * 执行批处理主流程：拉取待签名订单、构建交易、入库签名交易并推送到签名队列。
     */
    public void execute() {
        String chain = chain();
        if (!runtimeConfigService.isTaskEnabled(chain, WalletRuntimeConfigService.TASK_WITHDRAW)) {
            log.debug("UTXO批处理跳过 币种:{} DB withdraw switch disabled", chain);
            return;
        }
        currency = blockchainRuntimeService.assetMetadata(chain);
        log.info("UTXO批处理（提现+归集）开始 币种:{}", currency.getName());

        try {
            while (true) {
                List<WithdrawalOrderRecord> orders =
                        chainJdbcRepository.listWithdrawalsForSigning(chain, chain, COUNT);
                if (CollectionUtils.isEmpty(orders)) {
                    break;
                }
                List<WithdrawRecord> records = orders.stream()
                        .map(order -> toWithdrawRecord(order, currency))
                        .toList();
                UUID tenantId = Objects.requireNonNull(
                        orders.getFirst().getTenantId(), "withdrawal tenantId is required");
                if (orders.stream().anyMatch(order -> !tenantId.equals(order.getTenantId()))) {
                    throw new IllegalStateException("withdraw batch cannot contain multiple tenants");
                }
                WithdrawTransaction transaction = buildTransaction(tenantId, records);
                if (transaction == null) {
                    log.error("UTXO批处理异常 交易创建失败 币种:{}", currency.getName());
                    break;
                }
                // 将签名交易对象序列化后推送到签名服务队列
                String val = JSONObject.toJSONString(transaction);

                if (SINGLE_SIG_CURRENCY.contains(currency)) {
                    REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_SECOND_KEY, val);
                    log.info("交易推送到第二次签名服务{}", transaction.getId());
                } else {
                    REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY, val);
                    log.info("交易推送到第一次签名服务{}", transaction.getId());
                }
                log.info("构建交易成功 id:{}", transaction.getId());

                // 说明数据库中没有等待签名的交易了，不需要继续循环
                if (records.size() < COUNT) {
                    break;
                }
            }
        } catch (Throwable e) {
            log.error("UTXO批处理扫描数据,构建交易,发送到redis队列出现异常 币种id:{}", currency.getName(), e);
        }

        log.info("UTXO批处理结束 币种:{}", currency.getName());
    }

    /**
     * 返回具体子链标识。子类必须实现，例如 BTC/BCH/LTC/DOGE。
     */
    protected abstract String chain();

    /**
     * 根据一批提现记录构建签名交易：
     * 1) 计算总金额与找零需求；2) 拉取足量 UTXO；3) 重组输出与签名数据；
     * 4) 入签名表；5) 锁定 UTXO 与更新订单状态。
     */
    protected WithdrawTransaction buildTransaction(UUID tenantId, List<WithdrawRecord> records) {
        log.info("构建提现交易对象开始");
        int size = 1;
        WithdrawTransaction transaction = null;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal withdrawAmount = BigDecimal.ZERO;
        for (WithdrawRecord record : records) {
            totalAmount = totalAmount.add(record.getBalance()).add(record.getFee());
            withdrawAmount = withdrawAmount.add(record.getBalance());
        }
        Integer redisFeeRate = REDIS.getInt(Constants.WALLET_FEE + currency.getIndex());
        int feeRate = redisFeeRate == null || redisFeeRate <= 0 ? defaultFeeRate() : redisFeeRate;
        long depositConfirmationThreshold = blockchainRuntimeService.depositConfirmationThreshold(currency);
        int offset = 0;

        // 逐步按分页方式选取可花费 UTXO，直到余额足够覆盖本批提现+费用
        LinkedList<UtxoTransaction> utxos = new LinkedList<>();
        BigDecimal walletAmount = BigDecimal.ZERO;
        while (true) {
            List<UtxoTransaction> tmps = listCandidateUtxos(
                    tenantId, depositConfirmationThreshold, size, offset);
            if (CollectionUtils.isEmpty(tmps)) {
                log.error("构建交易失败 钱包余额不足");
                return null;
            }
            utxos.addAll(tmps);
            // 因为 page size 为 1，所以查询结果中只有一条数据
            UtxoTransaction utxo = tmps.get(0);
            walletAmount = walletAmount.add(utxo.getBalance());
            if (walletAmount.compareTo(requiredAmount(totalAmount, withdrawAmount, utxos.size(), records.size(), feeRate)) > 0) {
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
            String chain = currency.getName().toUpperCase(Locale.ROOT);
            Address address = chainJdbcRepository.findChainAddressByAddress(
                            tenantId, chain, utxo.getAddress())
                    .map(record -> toAddress(record, currency))
                    .orElseThrow(() -> new IllegalStateException(
                            "missing chain_address for " + chain + " UTXO address " + utxo.getAddress()));
            addresses.add(address);
            if (walletAmount.compareTo(requiredAmount(totalAmount, withdrawAmount, utxos.size(), records.size(), feeRate)) > 0) {
                break;
            }
        }

        // 初始化待签名 payload
        JSONObject signature = new JSONObject();
        Address changeAddress = defaultHotChangeAddress(tenantId, currency);
        signature.put("utxos", utxos);
        signature.put("addresses", addresses);
        signature.put("withdraw", records);
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
                .signature(signature.toJSONString())
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
        String chain = currency.getName().toUpperCase(Locale.ROOT);
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
    private BigDecimal requiredAmount(BigDecimal userFeeRequired, BigDecimal withdrawAmount,
                                     int inputCount, int outputCount, int feeRate) {
        long feeSat = estimateNetworkFeeAtomic(Math.max(inputCount, 1), outputCount + 1, feeRate);
        BigDecimal networkFee = BigDecimal.valueOf(feeSat).divide(currency.getDecimal());
        BigDecimal dynamicRequired = withdrawAmount.add(networkFee);
        return dynamicRequired.max(userFeeRequired);
    }

    /**
     * 默认费率回退值（sat/vB）。
     */
    protected int defaultFeeRate() {
        return DEFAULT_FEE_RATE;
    }

    /**
     * 估算网络手续费，子类可按链策略覆盖。
     */
    protected long estimateNetworkFeeAtomic(int inputCount, int outputCount, int feeRate) {
        return P2wshFeeCalculator.calculateFeeSat(inputCount, outputCount, feeRate);
    }

    /**
     * 按分页从 DB 拉取可用 UTXO。
     */
    private List<UtxoTransaction> listCandidateUtxos(UUID tenantId, long depositConfirmationThreshold,
                                                     int limit,
                                                     int offset) {
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
