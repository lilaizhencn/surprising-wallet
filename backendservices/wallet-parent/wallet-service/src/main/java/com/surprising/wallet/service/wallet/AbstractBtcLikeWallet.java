package com.surprising.wallet.service.wallet;

import com.alibaba.fastjson.JSONObject;
import com.googlecode.jsonrpc4j.JsonRpcClientException;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.wallet.client.command.BtcLikeCommand;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.pojo.rpc.BtcLikeBlock;
import com.surprising.wallet.common.pojo.rpc.BtcLikeRawTransaction;
import com.surprising.wallet.common.pojo.rpc.ScriptPubKey;
import com.surprising.wallet.common.pojo.rpc.TxOutput;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.config.PubKeyConfig;
import com.surprising.wallet.service.criteria.WithdrawTransactionExample;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.chain.BitcoinLikeSettlementService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.ScriptException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.surprising.wallet.common.utils.Constants.UNSPENT_TX_ID;


/**
 * @author atomex
 */
@Slf4j
public abstract class AbstractBtcLikeWallet extends AbstractWallet implements IWallet {

    @Autowired
    protected Constants CONSTANT;
    @Autowired
    protected PubKeyConfig pubKeyConfig;
    @Autowired
    protected ChainJdbcRepository chainJdbcRepository;
    @Autowired
    protected BitcoinLikeSettlementService bitcoinLikeSettlementService;
    protected BtcLikeCommand command;
    protected Long height = 0L;

    public void setCommand(BtcLikeCommand com) {
        command = com;
    }

    /**
     * 获得当前币种的精度，用于精度转换
     *
     * @return
     */
    @Override
    public BigDecimal getDecimal() {
        return BigDecimal.valueOf(10000_0000L);
    }

    /**
     * 获取当前币的网络参数
     *
     * @return
     */
    public NetworkParameters getNetworkParameters() {
        throw new RuntimeException("Not Support GetNetworkParameters Method");
    }


    /**
     * 生成新地址
     *
     * @param userId 用户id
     * @param biz    业务类型： spot、c2c 等
     * @return
     */
    @Override
    @Transactional(rollbackFor = Throwable.class)
    public synchronized Address genNewAddress(Long userId, Integer biz) {
        log.info("用户获取新地址, 用户id:{}, 业务线:{}, 币种:{} 开始获取", userId, biz, getCurrency().name());

        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Address address = buildNextAddress(userId, biz);
                chainJdbcRepository.upsertChainAddress(toChainAddressRecord(address));
                log.info("用户获取新地址, 用户id:{}, 业务线:{}, 币种:{} 第{}个 结束",
                        userId, biz, getCurrency().name(), address.getIndex());
                return address;
            } catch (DuplicateKeyException e) {
                log.warn("生成地址遇到并发重复, userId={}, biz={}, attempt={}", userId, biz, attempt + 1);
            }
        }
        throw new IllegalStateException("failed to allocate a unique address index");
    }

    private Address buildNextAddress(Long userId, Integer biz) {
        String chain = getCurrency().getName().toUpperCase(Locale.ROOT);
        int index = chainJdbcRepository.findMaxChainAddressIndex(
                        chain, chain, userId, biz, "DEPOSIT")
                .map(value -> Math.toIntExact(value + 1))
                .orElse(0);
        /*
        hd的公钥推导path: bip44-currency-biz-userId-index
         */
        return buildAddress(userId, biz, index);
    }

    protected Address buildAddress(Long userId, Integer biz, int index) {
        PubKeyConfig.AddressMetadata metadata = pubKeyConfig.genThreeTwoAddressMetadata(
                getNetworkParameters(), getBip44CoinType(), userId.intValue(), biz, index);
        return Address.builder()
                .address(metadata.getAddress())
                .network(getNetworkName())
                .scriptType("P2WSH")
                .redeemScript(metadata.getRedeemScript())
                .witnessScript(metadata.getWitnessScript())
                .derivationPath(metadata.getPath())
                .publicKeys(metadata.getPublicKeys())
                .biz(biz)
                .currency(getCurrency().getName())
                .userId(userId)
                .index(index)
                .balance(BigDecimal.ZERO)
                .nonce(0)
                .status((byte) Constants.WAITING)
                .createDate(Date.from(Instant.now()))
                .updateDate(Date.from(Instant.now()))
                .build();
    }

    private ChainAddressRecord toChainAddressRecord(Address address) {
        String chain = getCurrency().getName().toUpperCase(Locale.ROOT);
        return ChainAddressRecord.builder()
                .chain(chain)
                .assetSymbol(chain)
                .accountId(address.getUserId().toString())
                .userId(address.getUserId())
                .biz(address.getBiz())
                .addressIndex(address.getIndex().longValue())
                .address(address.getAddress())
                .ownerAddress(null)
                .derivationPath(address.getDerivationPath())
                .walletRole("DEPOSIT")
                .enabled(true)
                .build();
    }

    /**
     * 解析 {@link org.bitcoinj.core.Transaction} 格式的交易为本地 UtxoTransaction 格式
     */
    public List<TransactionDTO> convertFromBjTx(Transaction transaction, Long height) {
        List<UtxoTransaction> txs = null;
        if (ObjectUtils.isEmpty(transaction)) {
            return new LinkedList<>();
        }
        List<TransactionOutput> outputs = transaction.getOutputs();
        Long bestHeight = getBestHeight();


        txs = outputs.parallelStream()
                .map((output) -> {
                    UtxoTransaction utxo = null;
                    try {
                        String addressStr = normalizeScannedAddress(
                                output.getScriptPubKey().getToAddress(getNetworkParameters()).toString());
                        Address address = addressService.getAddress(addressStr, getCurrency());
                        if (ObjectUtils.isEmpty(address)) {
                            return utxo;
                        }

                        BigDecimal balance = new BigDecimal(output.getValue().getValue());
                        Long confirm = 0L;
                        if (height > 0L) {
                            confirm = bestHeight >= height ? bestHeight - height + 1 : 0;
                        }
                        utxo = UtxoTransaction.builder()
                                .address(addressStr)
                                .blockHeight(height)
                                .confirmNum(confirm)
                                .txId(transaction.getTxId().toString())
                                .balance(balance.divide(getDecimal()))
                                .seq(new Integer(output.getIndex()).shortValue())
                                .spent((byte) 0)
                                .biz(address.getBiz())
                                .currency(getCurrency().getIndex())
                                .spentTxId(UNSPENT_TX_ID)
                                .status((byte) Constants.WAITING)
                                .credited(false)
                                .createDate(Date.from(Instant.now()))
                                .updateDate(Date.from(Instant.now()))
                                .build();
                    } catch (ScriptException e) {
                        utxo = null;
                    } catch (Throwable e) {
                        AbstractBtcLikeWallet.log.error("convertFromBjTx map error", e);
                    }
                    return utxo;
                })
                .filter((utxo) -> utxo != null).collect(Collectors.toList());
        List<TransactionDTO> dtos = new LinkedList<>();
        if (!CollectionUtils.isEmpty(txs)) {
            persistScannedUtxos(txs);
            dtos = txs.parallelStream().map(this::convertUtxoToDto).collect(Collectors.toList());
        }
        return dtos;
    }

    public List<TransactionDTO> convertFromBjTx(Transaction transaction) {
        return convertFromBjTx(transaction, 0L);
    }


    @Override
    public boolean withdraw(WithdrawRecord record) {
        return true;
    }

    /**
     * 返回当前币种钱包中的余额
     *
     * @return
     */
    @Override
    public BigDecimal getBalance() {
        return getBalance(getCurrency());
    }

    public BigDecimal getBalance(CurrencyEnum currencyEnum) {
        String currencyName = currencyEnum.getName();
        log.info("get {} Balance begin", currencyName);
        try {
            if (usesUnifiedUtxoModel(currencyEnum)) {
                String chain = currencyEnum.getName().toUpperCase(Locale.ROOT);
                BigDecimal balance = chainJdbcRepository.sumAvailableUtxoAmount(chain, chain);
                log.info("get {} Balance end", currencyName);
                return balance;
            }
            throw new IllegalArgumentException("unsupported non-unified BTC-like currency " + currencyEnum);
        } catch (Exception e) {
            log.error("{} getBalance error", currencyEnum, e);

        }
        return BigDecimal.ZERO;
    }


    /**
     * 扫描高度:height 区块，获得相关交易
     *
     * @return
     */
    @Override
    public List<TransactionDTO> findRelatedTxs(Long height) {
        String currencyName = getCurrency().getName();
        log.info("{} findRelatedTxs, height:{} begin", currencyName, height);
        String hash = getBlockHash(height);
        BtcLikeBlock block = command.getBlock(hash);
        if (block == null) {
            log.error("block is null ,blockHash:{}！", hash);
            return null;
        }
        List<String> txidList = block.getTx();
        List<UtxoTransaction> results = txidList.parallelStream()
                .map((txid) -> {
                    //先更新提现交易的的状态
                    updateWithdrawTXId(txid, getCurrency());
                    List<UtxoTransaction> utxos = getUtxo(txid, height);
                    return utxos;
                })
                .filter((utxos) -> !CollectionUtils.isEmpty(utxos))
                .collect(LinkedList::new, LinkedList::addAll, LinkedList::addAll);
        List<TransactionDTO> dtos = new LinkedList<>();
        if (!CollectionUtils.isEmpty(results)) {
            persistScannedUtxos(results);
            dtos = results.parallelStream().map(this::convertUtxoToDto).collect(Collectors.toList());
        }

        AbstractBtcLikeWallet.log.info("{} findRelatedTxs, height:{} end", currencyName, height);
        return dtos;
    }


    /**
     * 当发现txid是我们发出的交易时，更新交易的状态
     *
     * @param txid
     */
    @Override
    protected void updateWithdrawTXId(String txid, CurrencyEnum currency) {
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        WithdrawTransactionExample withExam = new WithdrawTransactionExample();
        withExam.createCriteria().andTxIdEqualTo(txid);
        Optional<WithdrawTransaction> oneByExample = withdrawTransactionService.getOneByExample(withExam, table);
        if (oneByExample.isPresent()) {
            WithdrawTransaction withdrawTransaction = oneByExample.get();
            JSONObject signature = JSONObject.parseObject(withdrawTransaction.getSignature());
            int confirmations = getConfirm(txid);
            if (confirmations < getWithdrawConfirmationThreshold()) {
                markBitcoinLikeConfirming(signature, txid);
                return;
            }
            bitcoinLikeSettlementService.settleConfirmed(withdrawTransaction, txid, currency);
        }
    }

    private List<UtxoTransaction> getUtxo(String txid, Long height) {
        BtcLikeRawTransaction rawTransaction = getRawTransaction(txid);
        if (rawTransaction == null || CollectionUtils.isEmpty(rawTransaction.getVout())) {
            return new LinkedList<>();
        }

        List<TxOutput> vout = rawTransaction.getVout();
        List<UtxoTransaction> utxoTransactions = vout.parallelStream()
                .map((output) -> {

                    ScriptPubKey pubKey = output.getScriptPubKey();
                    String addressStr = normalizeScannedAddress(extractOutputAddress(pubKey));
                    if (!StringUtils.hasText(addressStr)) {
                        return null;
                    }
                    Address address = addressService.getAddress(addressStr, getCurrency());
                    Long confirm = this.height - height + 1;

                    if (ObjectUtils.isEmpty(address)) {
                        return null;
                    }

                    UtxoTransaction utxo = UtxoTransaction.builder()
                            .balance(output.getValue())
                            .address(addressStr)
                            .biz(address.getBiz())
                            .currency(getCurrency().getIndex())
                            .spent((byte) 0)
                            .spentTxId(UNSPENT_TX_ID)
                            .seq(new Integer(output.getN()).shortValue())
                            .txId(txid)
                            .blockHeight(height)
                            .confirmNum(confirm)
                            .status((byte) Constants.WAITING)
                            .credited(false)
                            .createDate(Date.from(Instant.now()))
                            .updateDate(Date.from(Instant.now()))
                            .build();
                    return utxo;

                })
                .filter(utxo -> !ObjectUtils.isEmpty(utxo))
                .collect(Collectors.toList());
        return utxoTransactions;
    }

    private String extractOutputAddress(ScriptPubKey pubKey) {
        if (pubKey == null) {
            return null;
        }
        if (getCurrency() == CurrencyEnum.BCH && !CollectionUtils.isEmpty(pubKey.getCashAddrs())) {
            return pubKey.getCashAddrs().get(0);
        }
        if (StringUtils.hasText(pubKey.getAddress())) {
            return pubKey.getAddress();
        }
        if (!CollectionUtils.isEmpty(pubKey.getAddresses())) {
            return pubKey.getAddresses().get(0);
        }
        return null;
    }


    @Override
    public Long getBestHeight() {
        height = command.getBlockCount();
        return height;
    }

    @Override
    public String sendRawTransaction(WithdrawTransaction transaction) {

        String expectedTxId = getTxId(transaction);
        try {
            //final String txId = transaction.getTxId();
            JSONObject signature = JSONObject.parseObject(transaction.getSignature());
            //utxo 更新spentTxId

            String raw = signature.getString("rawTransaction");
            String txId;
            if (transactionExists(expectedTxId)) {
                txId = expectedTxId;
            } else {
                try {
                    txId = command.sendRawTransaction(raw);
                } catch (Throwable broadcastError) {
                    if (!transactionExists(expectedTxId)) {
                        throw broadcastError;
                    }
                    txId = expectedTxId;
                }
            }
            return txId;
        } catch (Throwable e) {
            AbstractBtcLikeWallet.log.error("sendRawTransaction error", e);
            return "";
        }
    }

    private boolean transactionExists(String txId) {
        try {
            return getRawTransaction(txId) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public String getBlockHash(Long height) {
        return command.getBlockHash(height);
    }


    public BtcLikeRawTransaction getRawTransaction(String txid) {
        try {
            return command.getRawTransaction(txid, true);
        } catch (JsonRpcClientException e) {
            return command.getRawTransaction(txid, 1);
        }

//        final String rawTx = this.command.getRawTransactionStr(txid);
//        return this.command.decodeRawTransactionStr(rawTx);
    }

    @Override
    public boolean checkAddress(String addressStr) {
        boolean valid = false;
        if (StringUtils.hasText(addressStr)) {
            try {
                        org.bitcoinj.base.Address parsedAddress =
                        org.bitcoinj.base.Address.fromString(getNetworkParameters(), addressStr);
                if (!ObjectUtils.isEmpty(parsedAddress)) {
                    valid = true;
                }
            } catch (AddressFormatException e) {
                AbstractBtcLikeWallet.log.error("{} is not valid", addressStr, e);
            }
        }
        return valid;

    }

    @Override
    public int getConfirm(String txId) {
        BtcLikeRawTransaction transaction = getRawTransaction(txId);
        if (ObjectUtils.isEmpty(transaction)) {
            return -1;
        } else {
            return transaction.getConfirmations();
        }
    }

    @Override
    public String getTxId(WithdrawTransaction transaction) {
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());
        String raw = signature.getString("rawTransaction");
        Transaction signCompleteTx = Transaction.read(java.nio.ByteBuffer.wrap(java.util.HexFormat.of().parseHex(raw)));
        return signCompleteTx.getTxId().toString();
    }

    @Override
    public void updateTXConfirmation(CurrencyEnum currency) {
        log.info("{} 更新交易确认数开始", getCurrency().getName());
        updateUnifiedUtxoConfirmations(currency);
        updatePendingWithdrawConfirmations(currency);
        log.info("{} 更新交易确认数结束", currency.getName());
    }

    private void updateUnifiedUtxoConfirmations(CurrencyEnum currency) {
        int pageSize = 500;
        int offset = 0;
        String chain = currency.getName().toUpperCase(Locale.ROOT);
        while (true) {
            List<UtxoTransaction> utxos = chainJdbcRepository.listAvailableUtxosBelowConfirmations(
                    chain, chain, currency.getConfirmNum(), pageSize, offset);
            for (UtxoTransaction utxo : utxos) {
                Integer confirm = getConfirm(utxo.getTxId());
                long normalizedConfirmations = confirm != null && confirm > 0 ? confirm : 0L;
                utxo.setConfirmNum(normalizedConfirmations);
                chainJdbcRepository.updateUtxoConfirmations(
                        chain, utxo.getTxId(), utxo.getSeq(), (int) normalizedConfirmations);
                if (chainJdbcRepository.depositRecordExists(chain, utxo.getTxId(), utxo.getSeq())
                        && enrichUtxoMetadata(utxo, currency)) {
                    TransactionDTO dto = convertUtxoToDto(utxo);
                    transactionService.saveTransaction(dto);
                }
            }
            if (utxos.size() < pageSize) {
                break;
            }
            offset += pageSize;
        }
    }

    private void updatePendingWithdrawConfirmations(CurrencyEnum currency) {
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        WithdrawTransactionExample example = new WithdrawTransactionExample();
        example.createCriteria().andStatusEqualTo(Constants.SENT);
        List<WithdrawTransaction> pending = withdrawTransactionService.getByExample(example, table);
        for (WithdrawTransaction transaction : pending) {
            if (!StringUtils.hasText(transaction.getTxId())) {
                continue;
            }
            int confirmations = getConfirm(transaction.getTxId());
            if (confirmations >= getWithdrawConfirmationThreshold()) {
                updateWithdrawTXId(transaction.getTxId(), currency);
            } else if (confirmations > 0) {
                markBitcoinLikeConfirming(JSONObject.parseObject(transaction.getSignature()), transaction.getTxId());
            }
        }
    }

    private void markBitcoinLikeConfirming(JSONObject signature, String txId) {
        String chain = getCurrency().getName().toUpperCase(Locale.ROOT);
        if ("collection".equals(signature.getString("type"))) {
            chainJdbcRepository.updateCollectionStatus(
                    chain, signature.getString("collectionId"), "CONFIRMING", txId, null,
                    signature.toJSONString());
            return;
        }
        List<WithdrawRecord> records = signature.getJSONArray("withdraw").toJavaList(WithdrawRecord.class);
        records.forEach(record -> chainJdbcRepository.updateWithdrawalStatus(
                chain, record.getWithdrawId(), "CONFIRMING", null, txId, null));
    }

    protected String getNetworkName() {
        return getNetworkParameters().getPaymentProtocolId();
    }

    protected boolean usesUnifiedUtxoModel() {
        return usesUnifiedUtxoModel(getCurrency());
    }

    protected boolean usesUnifiedUtxoModel(CurrencyEnum currency) {
        return currency == CurrencyEnum.BTC
                || currency == CurrencyEnum.LTC
                || currency == CurrencyEnum.DOGE
                || currency == CurrencyEnum.BCH;
    }

    protected String normalizeScannedAddress(String address) {
        return address;
    }

    protected int getBip44CoinType() {
        return getCurrency().getBip44CoinType();
    }

    protected long getWithdrawConfirmationThreshold() {
        return getCurrency().getWithdrawConfirmNum();
    }

    private void syncUnifiedUtxos(List<UtxoTransaction> utxos) {
        String chain = getCurrency().getName().toUpperCase(Locale.ROOT);
        String symbol = chain;
        for (UtxoTransaction utxo : utxos) {
            chainJdbcRepository.upsertUtxo(
                    chain,
                    symbol,
                    utxo.getTxId(),
                    utxo.getSeq(),
                    utxo.getAddress(),
                    utxo.getBalance(),
                    utxo.getBlockHeight(),
                    utxo.getConfirmNum().intValue(),
                    Boolean.TRUE.equals(utxo.getCredited()));
        }
    }

    private void persistScannedUtxos(List<UtxoTransaction> utxos) {
        syncUnifiedUtxos(utxos);
    }

    private boolean enrichUtxoMetadata(UtxoTransaction utxo, CurrencyEnum currency) {
        Address address = addressService.getAddress(utxo.getAddress(), currency);
        if (address == null) {
            return false;
        }
        utxo.setBiz(address.getBiz());
        utxo.setCurrency(currency.getIndex());
        return true;
    }
}
