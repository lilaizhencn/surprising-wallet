package com.surprising.wallet.service.service;

import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.currency.BizEnum;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.asset.AssetRoutingService;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.wallet.IWallet;
import com.surprising.wallet.service.wallet.WalletContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static com.surprising.wallet.common.utils.Constants.WALLET_DEPOSIT_KEY;

/**
 * @author lilaizhen
 */
@Slf4j
@Component
public class TransactionService {
    @Autowired
    AddressService addressService;

    @Autowired
    WalletContext walletContext;

    @Autowired
    ChainJdbcRepository chainJdbcRepository;
    @Autowired
    AssetRoutingService assetRoutingService;
    @Autowired
    WalletRuntimeConfigService runtimeConfigService;

    /**
     * 充值，把充值交易推送到各自的业务线队列
     */
    public void saveTransaction(List<TransactionDTO> dtos) {
        log.info("saveTransactions dto begin");
        dtos.forEach(this::saveTransaction);
        log.info("saveTransactions dto end");
    }

    @Transactional(rollbackFor = Throwable.class, isolation = Isolation.READ_COMMITTED)
    public void saveTransaction(TransactionDTO dto) {
        log.info("saveTransaction dto: {} begin", dto.getTxId());
        RuntimeAsset currency = assetRoutingService.runtimeAsset(dto.getCurrency());
        // Wallet app deposit addresses currently use biz=0; only hot/internal addresses are skipped.
        if (BizEnum.INTERNAL.getIndex() == dto.getBiz()
                && isInternalAddress(dto, currency)) {
            log.warn("internal类型的转账不需要通知 交易id:{}", dto.getTxId());
            return;
        }
        runtimeConfigService.requireTaskEnabled(chainName(currency), WalletRuntimeConfigService.TASK_SCAN,
                "legacy saveTransaction");
        long requiredConfirmations =
                walletContext.getWallet(currency).getDepositConfirmationThreshold();
        if (dto.getConfirmNum() != null && dto.getConfirmNum() >= requiredConfirmations) {
            creditDepositIfNeeded(dto, currency, requiredConfirmations);
        }
        // String depositKey = WALLET_DEPOSIT_KEY + dto.getBiz();
        String depositKey = WALLET_DEPOSIT_KEY;
        String val = JSONObject.toJSONString(dto);
        REDIS.rPush(depositKey, val);
        log.info("saveTransaction dto: {} end", dto.getTxId());
    }


    /**
     * 把提现记录入库，账户类型的币直接发出提现请求，但是utxo类型的币在 {@link com.surprising.wallet.jobs.withdraw}中批量汇出
     */
    @Transactional(rollbackFor = {Throwable.class}, isolation = Isolation.READ_COMMITTED)
    public boolean withdraw(WithdrawRecord record) {
        log.info("提现操作 开始 提现id:{}", record.getWithdrawId());

        RuntimeAsset currency = assetRoutingService.runtimeAsset(record.getCurrency());
        runtimeConfigService.requireTaskEnabled(chainName(currency), WalletRuntimeConfigService.TASK_WITHDRAW,
                "legacy withdraw");
        if (isUnifiedBitcoinLike(currency)) {
            String chain = chainName(currency);
            if (chainJdbcRepository.findWithdrawalStatus(chain, record.getWithdrawId()).isPresent()) {
                log.info("提现操作 重复提现id已存在:{}", record.getWithdrawId());
                return true;
            }
            int created = chainJdbcRepository.createWithdrawalOrder(
                    record.getWithdrawId(),
                    record.getUserId(),
                    chain,
                    chain,
                    null,
                    record.getUserId().toString(),
                    record.getAddress(),
                    record.getBalance(),
                    record.getFee() == null ? BigDecimal.ZERO : record.getFee());
            if (created == 0) {
                return true;
            }
            BigDecimal frozenAmount = withdrawFrozenAmount(record);
            if (!chainJdbcRepository.freezeLedgerBalance(
                    chain, chain, record.getUserId().toString(), frozenAmount)) {
                chainJdbcRepository.updateWithdrawalStatus(
                        chain, record.getWithdrawId(), "FAILED", null, null, "ledger freeze failed");
                return false;
            }
            chainJdbcRepository.updateWithdrawalStatus(
                    chainName(currency), record.getWithdrawId(), "FROZEN", null, null, null);
            log.info("提现操作 结束 提现id:{}", record.getWithdrawId());
            return true;
        }

        throw new IllegalArgumentException(
                "legacy withdraw API runtime is disabled for currency " + record.getCurrency());
    }

    /**
     * 把已经签好的交易发送出去
     * 更新withdrawrecord表和utxo表中的txid
     * 把数据推送到 {@Link Constants.WALLET_WITHDRAW_TX_BIZ_KEY}各个业务线的队列，回写txid
     *
     * @param transaction 已经签好的交易
     */
    @Transactional(rollbackFor = Throwable.class, isolation = Isolation.READ_COMMITTED)
    public boolean sendWithdrawTransaction(WithdrawTransaction transaction) {
        log.info("广播签名后的交易 开始 币种id:{} 交易id:{}", transaction.getCurrency(), transaction.getId());
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());
        RuntimeAsset currency = transactionAsset(transaction);

        //签名是否成功
        if (!signature.containsKey("valid") || !signature.getBoolean("valid")) {
            log.error("广播签名后的交易 签名失败 币种id:{} 交易id:{}", transaction.getCurrency(), transaction.getId());
            if (isUnifiedBitcoinLike(currency)) {
                failBitcoinLikeTransaction(transaction, currency, signature.getString("error"));
            }
            return true;
        }

        var persisted = isUnifiedBitcoinLike(currency)
                ? chainJdbcRepository.findBitcoinLikeSigningTransactionById(currency, transaction.getId())
                : java.util.Optional.<WithdrawTransaction>empty();
        if (persisted.isPresent()
                && persisted.get().getStatus() != null
                && persisted.get().getStatus() >= Constants.SENT
                && StringUtils.hasText(persisted.get().getTxId())
                && !"singing".equalsIgnoreCase(persisted.get().getTxId())
                && !"signing".equalsIgnoreCase(persisted.get().getTxId())) {
            log.info("广播签名后的交易 已完成幂等跳过 币种:{} 交易id:{} txid:{}",
                    currency.getName(), transaction.getId(), persisted.get().getTxId());
            return true;
        }
        IWallet wallet = walletContext.getWallet(currency);

        String txId = wallet.sendRawTransaction(transaction);

        if (!StringUtils.hasText(txId)) {
            log.error("广播签名后的交易 广播失败 币种id:{} 提现信息:{}", currency.getName(), transaction);
            if (isUnifiedBitcoinLike(currency)) {
                markBitcoinLikeRetrying(transaction, currency, signature, "broadcast failed");
            }
            return false;
        }

        // String txId = wallet.getTxId(transaction);
        transaction.setTxId(txId);
        //2 代表交易已发送
        short status = Constants.SENT;
        transaction.setStatus(status);
        transaction.setUpdateDate(Date.from(Instant.now()));
        if (isUnifiedBitcoinLike(currency)) {
            chainJdbcRepository.updateBitcoinLikeSigningTransaction(currency, transaction);
        } else {
            throw new IllegalArgumentException(
                    "legacy withdraw transaction runtime is disabled for currency " + transaction.getCurrency());
        }

        List<WithdrawRecord> records = signature.getJSONArray("withdraw") == null
                ? List.of()
                : signature.getJSONArray("withdraw").toJavaList(WithdrawRecord.class);
        records.forEach((record) -> {
            record.setTxId(txId);
            record.setStatus((byte) status);
            record.setUpdateDate(Date.from(Instant.now()));
            chainJdbcRepository.updateWithdrawalStatus(
                    chainName(currency), record.getWithdrawId(), "SENT", null, txId, null);
            // String key = Constants.WALLET_WITHDRAW_TX_BIZ_KEY + record.getBiz();
            String key = Constants.WALLET_WITHDRAW_TX_BIZ_KEY;
            String val = JSONObject.toJSONString(record);
            REDIS.lPush(key, val);
        });
        if (isUnifiedBitcoinLike(currency) && "collection".equals(signature.getString("type"))) {
            chainJdbcRepository.updateCollectionStatus(
                    chainName(currency),
                    signature.getString("collectionId"),
                    "SENT",
                    txId,
                    null,
                    transaction.getSignature());
        }
        log.info("广播签名后的交易 成功 更新数据库完成 币种id:{} 交易id:{}", currency.getName(), transaction.getTxId());
        return true;
    }

    private void markBitcoinLikeRetrying(WithdrawTransaction transaction, RuntimeAsset currency,
                                         JSONObject signature, String error) {
        String chain = chainName(currency);
        chainJdbcRepository.markBitcoinLikeSigningError(currency, transaction.getId(), error);
        if ("collection".equals(signature.getString("type"))) {
            chainJdbcRepository.updateCollectionStatus(
                    chain, signature.getString("collectionId"), "RETRYING", null, error,
                    transaction.getSignature());
            return;
        }
        List<WithdrawRecord> records = signature.getJSONArray("withdraw").toJavaList(WithdrawRecord.class);
        records.forEach(record -> chainJdbcRepository.updateWithdrawalStatus(
                chain, record.getWithdrawId(), "RETRYING", null, null, error));
    }

    private void failBitcoinLikeTransaction(WithdrawTransaction transaction, RuntimeAsset currency, String error) {
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());
        String lockRef = transaction.getId().toString();
        String chain = chainName(currency);
        chainJdbcRepository.releaseUtxos(chain, lockRef);
        transaction.setStatus(Constants.DELETE);
        transaction.setUpdateDate(Date.from(Instant.now()));
        chainJdbcRepository.updateBitcoinLikeSigningTransaction(currency, transaction);
        chainJdbcRepository.markBitcoinLikeSigningError(currency, transaction.getId(), error);

        if ("collection".equals(signature.getString("type"))) {
            chainJdbcRepository.updateCollectionStatus(
                    chain, signature.getString("collectionId"), "FAILED", null, error,
                    transaction.getSignature());
            return;
        }
        List<WithdrawRecord> records = signature.getJSONArray("withdraw").toJavaList(WithdrawRecord.class);
        records.forEach(record -> {
            BigDecimal amount = withdrawFrozenAmount(record);
            String debitAccountId = withdrawalDebitAccount(chain, record);
            chainJdbcRepository.releaseLockedBalance(
                    chain, chain, debitAccountId, amount);
            chainJdbcRepository.updateWithdrawalStatus(
                    chain, record.getWithdrawId(), "FAILED", null, null, error);
            record.setStatus((byte) Constants.DELETE);
            record.setUpdateDate(Date.from(Instant.now()));
        });
    }

    private void creditDepositIfNeeded(
            TransactionDTO dto, RuntimeAsset currency, long requiredConfirmations) {
        UtxoKey utxoKey = UtxoKey.parse(dto.getTxId());
        if (utxoKey == null) {
            return;
        }
        Address address = addressService.getAddress(dto.getAddress(), currency);
        if (address == null || address.getUserId() == null || address.getUserId() <= 0) {
            return;
        }
        if (isUnifiedBitcoinLike(currency)) {
            String chain = chainName(currency);
            DepositEvent event = new DepositEvent(
                    ChainType.valueOf(chain),
                    chain,
                    utxoKey.txId,
                    null,
                    dto.getAddress(),
                    dto.getBalance(),
                    dto.getBlockHeight(),
                    dto.getConfirmNum().intValue(),
                    null,
                    null);
            boolean credited = chainJdbcRepository.recordAndCreditDeposit(
                    event,
                    utxoKey.seq,
                    Math.toIntExact(requiredConfirmations),
                    address.getUserId().toString());
            if (dto.getConfirmNum() >= requiredConfirmations) {
                chainJdbcRepository.markUtxoCredited(chain, utxoKey.txId, utxoKey.seq);
            }
            if (credited) {
                log.info("统一UTXO充值入账成功 userId:{} currency:{} tx:{} amount:{}",
                        address.getUserId(), currency.getName(), dto.getTxId(), dto.getBalance());
            }
            return;
        }
        log.warn("skip non-unified UTXO credit path currency:{} tx:{}",
                currency.getName(), dto.getTxId());
    }

    private BigDecimal withdrawFrozenAmount(WithdrawRecord record) {
        BigDecimal fee = record.getFee() == null ? BigDecimal.ZERO : record.getFee();
        return record.getBalance().add(fee);
    }

    private boolean isInternalAddress(TransactionDTO dto, RuntimeAsset currency) {
        Address address = addressService.getAddress(dto.getAddress(), currency);
        return address == null || address.getUserId() == null || address.getUserId() <= 0;
    }

    private String withdrawalDebitAccount(String chain, WithdrawRecord record) {
        return chainJdbcRepository.findWithdrawalOrder(chain, record.getWithdrawId())
                .map(order -> order.getDebitAccountId())
                .filter(StringUtils::hasText)
                .orElse(record.getUserId().toString());
    }

    private boolean isUnifiedBitcoinLike(RuntimeAsset currency) {
        return assetRoutingService.isBitcoinLikeRuntimeCurrency(currency);
    }

    private String chainName(RuntimeAsset currency) {
        return assetRoutingService.requireChainForRuntimeCurrencyId(currency.getIndex());
    }

    private RuntimeAsset transactionAsset(WithdrawTransaction transaction) {
        if (StringUtils.hasText(transaction.getChain())
                && StringUtils.hasText(transaction.getAssetSymbol())
                && transaction.getAssetDecimals() != null
                && transaction.getBip44CoinType() != null) {
            return RuntimeAsset.fromTransaction(transaction);
        }
        return assetRoutingService.runtimeAsset(transaction.getCurrency());
    }

    private static class UtxoKey {
        private final String txId;
        private final Short seq;

        private UtxoKey(String txId, Short seq) {
            this.txId = txId;
            this.seq = seq;
        }

        private static UtxoKey parse(String value) {
            if (!StringUtils.hasText(value)) {
                return null;
            }
            int split = value.lastIndexOf('-');
            if (split <= 0 || split == value.length() - 1) {
                return null;
            }
            try {
                return new UtxoKey(value.substring(0, split), Short.parseShort(value.substring(split + 1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
