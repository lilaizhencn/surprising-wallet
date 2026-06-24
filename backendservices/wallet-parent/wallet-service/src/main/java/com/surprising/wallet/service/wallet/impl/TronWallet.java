package com.surprising.wallet.service.wallet.impl;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.service.wallet.AbstractEthLikeWallet;
import com.surprising.wallet.service.wallet.IWallet;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.tron.TronWalletApi;
import org.tron.protos.Protocol;
import org.tron.wallet.util.ByteArray;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.*;

/**
 * @author lilaizhen
 * @data 12/04/2018
 */
@Slf4j
@Component
public class TronWallet extends AbstractEthLikeWallet implements IWallet {

    @Value("${atomex.tron.withdraw.address}")
    private String withdrawAddress;

    protected Long height = 0L;

    @Value("${atomex.tron.server}")
    private String tronServer;
    private RuntimeAsset currency;

    @PostConstruct
    public void init() {
//        log.info("tronserver url = {}", tronServer);
        TronWalletApi.init(tronServer);
        currency = loadRuntimeAssetByChain("TRON");
    }

    @Override
    public RuntimeAsset getCurrency() {
        return currency;
    }

    @Override
    public BigDecimal getDecimal() {
        return getCurrency().getDecimal();
    }

    @Override
    public Long getBestHeight() {
        height = TronWalletApi.getBlock(-1).getBlockHeader().getRawData().getNumber();
        return height;
    }

    @Override
    public String getWithdrawAddress() {
        return withdrawAddress;
    }

    @Override
    protected WithdrawTransaction buildTransaction(WithdrawRecord record) {
        RuntimeAsset currency = resolveRuntimeAsset(record);
        log.warn("{} legacy withdraw buildTransaction disabled; use withdrawal_order/signing_transaction flow",
                currency.getName());
        return null;
    }

    @Override
    protected WithdrawTransaction buildTransaction(WithdrawRecord record, String from, String type) {
        RuntimeAsset currency = resolveRuntimeAsset(record);
        log.warn("{} legacy account buildTransaction disabled; from={} type={}",
                currency.getName(), from, type);
        return null;
    }

    @Override
    public void transfer(String address, RuntimeAsset currency, Date deadline) {
        log.info("{} legacy TRON account transfer job disabled; use collection_record/DB Asset Model flow address={}",
                currency.getName(), address);
    }


    @Override
    public boolean checkAddress(String addressStr) {
        return TronWalletApi.addressValid(addressStr);
    }

    @Override
    public String sendRawTransaction(WithdrawTransaction transaction) {
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());
        String raw = signature.getString("rawTransaction");
        try {
            Protocol.Transaction signedTx = Protocol.Transaction.parseFrom(ByteArray.fromHexString(raw));
            boolean sendResult = TronWalletApi.broadcastTransaction(signedTx.toByteArray());
            if (sendResult) {
                return TronWalletApi.getTransactionHash(signedTx);
            }
            log.info("tron broadcast transaction result = {}", sendResult);
            return "";
        } catch (Throwable e) {
            log.error("");
            return "";
        }
    }

    @Override
    protected String formatDerivedAddress(ECKey ecKey) {
        return TronWalletApi.getAddress(ecKey.getPubKey());
    }


    /**
     * 这个方法的名字太笼统，主要干了下面几件事：
     * 1. 通过区块头查询当前区块交易列表
     * 2. 遍历交易列表：查询充值记录，用txid查询是不是系统发出的交易，更新状态为已确认，更新系统划转的交易记录为已确认
     * 3. 更新平台币种余额
     *
     * @param height
     * @return
     */
    @Override
    public List<TransactionDTO> findRelatedTxs(Long height) {
        log.info("legacy TRON account scanner disabled; use TronDepositScanner, height={}", height);
        return Collections.emptyList();
    }

    @Override
    protected BigDecimal getBalance(String address, RuntimeAsset currency) {
        Protocol.Account account = TronWalletApi.queryAccount(address);
        if (ObjectUtils.isEmpty(account)) {
            return BigDecimal.ZERO;
        }
        Long tranAmount = account.getBalance();
        BigDecimal amount = new BigDecimal(tranAmount);
        return amount.divide(getDecimal());
    }

    @Override
    public int getConfirm(String txId) {
        Optional<Protocol.TransactionInfo> transactionOptional = TronWalletApi.getTransactionInfoById(txId);
        if (!transactionOptional.isPresent()) {
            return -1;
        }
        Protocol.TransactionInfo transaction = transactionOptional.get();
        long blockHeight = transaction.getBlockNumber();
        Long bestHeight = getBestHeight();
        Long confirm = bestHeight - blockHeight + 1;
        return confirm.intValue();
    }
}
