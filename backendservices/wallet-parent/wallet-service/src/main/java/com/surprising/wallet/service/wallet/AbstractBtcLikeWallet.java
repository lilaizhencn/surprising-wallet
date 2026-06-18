package com.surprising.wallet.service.wallet;

import com.alibaba.fastjson.JSONObject;
import com.googlecode.jsonrpc4j.JsonRpcClientException;
import com.surprising.common.mybatis.pager.PageInfo;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.wallet.client.command.BtcLikeCommand;
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
import com.surprising.wallet.service.criteria.AddressExample;
import com.surprising.wallet.service.criteria.UtxoTransactionExample;
import com.surprising.wallet.service.criteria.WithdrawRecordExample;
import com.surprising.wallet.service.criteria.WithdrawTransactionExample;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.bitcoinj.core.*;
import org.bitcoinj.script.ScriptException;
import org.springframework.beans.factory.annotation.Autowired;
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
    public Address genNewAddress(Long userId, Integer biz) {
        log.info("用户获取新地址, 用户id:{}, 业务线:{}, 币种:{} 开始获取", userId, biz, getCurrency().name());

        AddressExample example = new AddressExample();
        example.createCriteria().andUserIdEqualTo(userId).andBizEqualTo(biz);

        ShardTable table = ShardTable.builder().prefix(getCurrency().getName()).build();
        List<Address> addressList = addressService.getByExample(example, table);
        int index = 0;
        /**
         * 获取该userId在biz业务线下面已经生成了多少地址
         */
        if (!CollectionUtils.isEmpty(addressList)) {

            Optional<Address> maxAddress = addressList.stream().max(Comparator.comparingInt(Address::getIndex));
            index = maxAddress.get().getIndex() + 1;
        }

        /*
        hd的公钥推导path: bip44-currency-biz-userId-index
         */
        CurrencyEnum currency = getCurrency();

        String addressStr = pubKeyConfig.genThree_TwoAddress(currency.getIndex(), userId.intValue(), biz, index);

        Address address = new Address();
        address.setAddress(addressStr);
        address.setBiz(biz);
        address.setCurrency(getCurrency().getName());
        address.setUserId(userId);
        address.setIndex(index);
        addressService.add(address, table);
        log.info("用户获取新地址, 用户id:{}, 业务线:{}, 币种:{} 第{}个 结束", userId, biz, getCurrency().name(), index);
        return address;
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
                        String addressStr = output.getScriptPubKey().getToAddress(Constants.NET_PARAMS).toString();
                        ShardTable table = ShardTable.builder().prefix(getCurrency().getName()).build();
                        Address address = addressService.getAddress(addressStr, table);
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
            ShardTable table = ShardTable.builder().prefix(getCurrency().getName()).build();
            utxoTransactionService.batchAddOnDuplicateKey(txs, table);
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
            UtxoTransactionExample example = new UtxoTransactionExample();
            example.createCriteria().andStatusLessThan((byte) Constants.CONFIRM).andConfirmNumGreaterThan(0L);
            ShardTable table = ShardTable.builder().prefix(currencyEnum.getName()).build();
            BigDecimal balance = utxoTransactionService.getTotalBalance(example, table);
            log.info("get {} Balance end", currencyName);
            return ObjectUtils.isEmpty(balance) ? BigDecimal.ZERO : balance;
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
            ShardTable table = ShardTable.builder().prefix(getCurrency().getName()).build();
            utxoTransactionService.batchAddOnDuplicateKey(results, table);
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
            withdrawTransaction.setStatus(Constants.CONFIRM);
            withdrawTransaction.setUpdateDate(Date.from(Instant.now()));
            withdrawTransactionService.editById(withdrawTransaction, table);

            UtxoTransactionExample example = new UtxoTransactionExample();
            example.createCriteria().andSpentTxIdEqualTo(txid);
            List<UtxoTransaction> utxoTransactions = utxoTransactionService.getByExample(example, table);
            if (!org.springframework.util.CollectionUtils.isEmpty(utxoTransactions)) {
                utxoTransactions.parallelStream().forEach((utxoTransaction -> utxoTransaction.setStatus((byte) Constants.CONFIRM)));
                utxoTransactionService.batchEdit(utxoTransactions, table);
            }

            WithdrawRecordExample recordExample = new WithdrawRecordExample();
            recordExample.createCriteria().andTxIdEqualTo(txid);
            List<WithdrawRecord> withdrawRecords = recordService.getByExample(recordExample, table);
            if (!org.springframework.util.CollectionUtils.isEmpty(withdrawRecords)) {
                withdrawRecords.parallelStream().forEach((record -> record.setStatus((byte) Constants.CONFIRM)));
                recordService.batchEdit(withdrawRecords, table);
            }
        }
    }

    private List<UtxoTransaction> getUtxo(String txid, Long height) {
        BtcLikeRawTransaction rawTransaction = getRawTransaction(txid);

        List<TxOutput> vout = rawTransaction.getVout();
        List<UtxoTransaction> utxoTransactions = vout.parallelStream()
                .map((output) -> {

                    ScriptPubKey pubKey = output.getScriptPubKey();
                    if (CollectionUtils.isEmpty(pubKey.getAddresses())) {
                        return null;
                    }
                    String addressStr = pubKey.getAddresses().get(0);
                    ShardTable table = ShardTable.builder().prefix(getCurrency().getName()).build();
                    Address address = addressService.getAddress(addressStr, table);
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
                            .createDate(Date.from(Instant.now()))
                            .updateDate(Date.from(Instant.now()))
                            .build();
                    return utxo;

                })
                .filter(utxo -> !ObjectUtils.isEmpty(utxo))
                .collect(Collectors.toList());
        return utxoTransactions;
    }


    @Override
    public Long getBestHeight() {
        height = command.getBlockCount();
        return height;
    }

    @Override
    public String sendRawTransaction(WithdrawTransaction transaction) {

        try {
            //更新utxo表中的txid
            UtxoTransactionExample utxoExam = new UtxoTransactionExample();
            utxoExam.createCriteria().andSpentTxIdEqualTo(transaction.getId().toString());
            ShardTable table = ShardTable.builder().prefix(getCurrency().getName()).build();
            List<UtxoTransaction> utxos = utxoTransactionService.getByExample(utxoExam, table);
            //final String txId = transaction.getTxId();
            JSONObject signature = JSONObject.parseObject(transaction.getSignature());
            //utxo 更新spentTxId

            String raw = signature.getString("rawTransaction");
            String txId = command.sendRawTransaction(raw);
            utxos.parallelStream().forEach((utxo) -> {
                utxoTransactionService.setSpentTxId(utxo, txId, getCurrency());
            });
            return txId;
        } catch (Throwable e) {
            AbstractBtcLikeWallet.log.error("sendRawTransaction error", e);
            return "";
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
                org.bitcoinj.core.Address address = org.bitcoinj.core.Address.fromString(Constants.NET_PARAMS, addressStr);
                if (!ObjectUtils.isEmpty(address)) {
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
        Transaction signCompleteTx = new Transaction(getNetworkParameters(), Utils.HEX.decode(raw));
        return signCompleteTx.getTxId().toString();
    }

    @Override
    public void updateTXConfirmation(CurrencyEnum currency) {
        log.info("{} 更新交易确认数开始", getCurrency().getName());
        int PAGE_SIZE = 500;
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        PageInfo page = new PageInfo();
        page.setPageSize(PAGE_SIZE);
        page.setSortItem("id");
        page.setSortType(PageInfo.SORT_TYPE_ASC);
        page.setStartIndex(0);
        UtxoTransactionExample example = new UtxoTransactionExample();
        example.createCriteria().andSpentTxIdEqualTo(UNSPENT_TX_ID).andConfirmNumLessThan(currency.getConfirmNum());

        while (true) {
            List<UtxoTransaction> utxos = utxoTransactionService.getByPage(page, example, table);
            utxos.parallelStream().forEach((utxo) -> {
                utxo.setCurrency(currency.getIndex());
                utxo.setUpdateDate(Date.from(Instant.now()));
                Integer confirm = getConfirm(utxo.getTxId());
                utxo.setConfirmNum(confirm.longValue() > 0 ? confirm.longValue() : 0);
                UtxoTransaction tmp = UtxoTransaction.builder().id(utxo.getId()).confirmNum(utxo.getConfirmNum()).build();
                utxoTransactionService.editById(tmp, table);
                TransactionDTO dto = convertUtxoToDto(utxo);
                transactionService.saveTransaction(dto);

            });

            if (utxos.size() < PAGE_SIZE) {
                break;
            }
            page.setStartIndex(page.getStartIndex() + PAGE_SIZE);
        }

        log.info("{} 更新交易确认数开始", currency.getName());
    }
}
