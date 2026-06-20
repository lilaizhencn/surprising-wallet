package com.surprising.wallet.service.wallet.impl;

import com.alibaba.fastjson.JSONObject;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.AccountTransaction;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.criteria.AccountTransactionExample;
import com.surprising.wallet.service.criteria.AddressExample;
import com.surprising.wallet.service.wallet.AbstractEthLikeWallet;
import com.surprising.wallet.service.wallet.IWallet;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.tron.TronWalletApi;
import org.tron.protos.Protocol;
import org.tron.wallet.util.ByteArray;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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

    @PostConstruct
    public void init() {
//        log.info("tronserver url = {}", tronServer);
        TronWalletApi.init(tronServer);
    }

    @Override
    public CurrencyEnum getCurrency() {
        return CurrencyEnum.TRX;
    }

    @Override
    public BigDecimal getDecimal() {
        return CurrencyEnum.TRX.getDecimal();
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
        return buildTransaction(record, withdrawAddress, Constants.WITHDRAW);
    }

    @Override
    protected WithdrawTransaction buildTransaction(WithdrawRecord record, String from, String type) {
        CurrencyEnum currency = CurrencyEnum.parseValue(record.getCurrency());
        AddressExample addrExam = new AddressExample();
        addrExam.createCriteria().andAddressEqualTo(from);
        ShardTable addressTable;
        addressTable = ShardTable.builder().prefix(CurrencyEnum.toMainCurrency(currency).getName()).build();

        Address address = addressService.getAndLockOneByExample(addrExam, addressTable);
        if (ObjectUtils.isEmpty(address)) {
            log.error("{} buildTransaction failed, from address not found: {}", currency.getName(), from);
            return null;
        }
        JSONObject signature = new JSONObject();

        signature.put("address", address);
        signature.put("from", from);
        signature.put("to", record.getAddress());
        signature.put("value", record.getBalance());
        signature.put("block", ByteArray.toHexString(TronWalletApi.getBlock(-1).toByteArray()));
        WithdrawTransaction transaction = WithdrawTransaction.builder()
                .balance(record.getBalance())
                .currency(currency.getIndex())
                .status(Constants.SIGNING)
                .txId(type)
                .signature(signature.toJSONString())
                .build();
        address.setUpdateDate(Date.from(Instant.now()));
        addressService.editById(address, addressTable);
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        withdrawTransactionService.add(transaction, table);
        log.info("{} buildTransaction end", currency.getName());
        return transaction;
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, isolation = Isolation.READ_UNCOMMITTED)
    public void transfer(String address, CurrencyEnum currency, Date deadline) {
        BigDecimal balance = getBalance(address, currency);

        //tron地址中的钱如果小于0.1个，会转账失败
        if (balance.compareTo(new BigDecimal("0.1")) > 0) {
            WithdrawRecord record = WithdrawRecord.builder()
                    .address(withdrawAddress)
                    .balance(balance)
                    .currency(currency.getIndex())
                    .build();
            WithdrawTransaction transferTransaction = buildTransaction(record, address, Constants.TRANSFER);
            if (transferTransaction == null) {
                log.error(" {} transfer error, buildTransaction failed", currency.getName());
                return;
            }
            //把交易推送到待签名队列
            String val = JSONObject.toJSONString(transferTransaction);
            //tron 只支持单签，所以直接用第二台机器签名
            REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_SECOND_KEY, val);
        }

        AccountTransaction transaction = new AccountTransaction();
        transaction.setStatus((byte) Constants.SIGNING);
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();

        AccountTransactionExample example = new AccountTransactionExample();
        example.createCriteria().andAddressEqualTo(address)
                .andConfirmNumGreaterThan(currency.getDepositConfirmNum())
                .andCreateDateLessThan(deadline);

        accountTransactionService.editByExample(transaction, example, table);
        log.info("{} transfer success, id:{}", currency.getName(), transaction.getId());

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
    public Address genNewAddress(Long userId, Integer biz) {
        AddressExample example = new AddressExample();
        example.createCriteria().andUserIdEqualTo(userId).andBizEqualTo(biz);

        ShardTable table = ShardTable.builder().prefix(getCurrency().getName()).build();
        List<Address> addressList = addressService.getByExample(example, table);
        int index = 0;

        //获取该userId在biz业务线下面已经生成了多少地址
        if (!CollectionUtils.isEmpty(addressList)) {

            Optional<Address> maxAddress = addressList.stream().max(Comparator.comparing(Address::getIndex));
            index = maxAddress.get().getIndex() + 1;
        }

        /*
         * TRON uses the same secp256k1 root key set as BTC/EVM.
         * Address generation must mirror wallet-sig2's m/44/currency/biz/user/index private-key path.
         */
        CurrencyEnum currency = getCurrency();
        ECKey ecKey = pubKeyConfig.NODE2.getChild(44).getChild(currency.getIndex()).getChild(biz).getChild(userId.intValue()).getChild(index).getEcKey();
        String addressStr = TronWalletApi.getAddress(ecKey.getPubKey());

        Address address = new Address();
        address.setAddress(addressStr);
        address.setBiz(biz);
        address.setCurrency(getCurrency().getName());
        address.setUserId(userId);
        address.setIndex(index);
        addressService.add(address, table);
        log.info("genNewAddress, userId:{}, biz:{}, currency:{} end", userId, biz, getCurrency().name());
        return address;
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
        log.info("tron findRelatedTxs height = {} ", height);
        Protocol.Block block = TronWalletApi.getBlock(height);
        if (ObjectUtils.isEmpty(block)) {
            log.error("tron findRelatedTxs height={} block is null", height);
            return null;
        }
        List<Protocol.Transaction> transactionsList = block.getTransactionsList();
        if (CollectionUtils.isEmpty(transactionsList)) {
            log.error("tron findRelatedTxs height={} is empty", height);
            return new ArrayList<>();
        }
        //记录是不是系统中的地址的提现记录
        Set<String> relatedAddress = ConcurrentHashMap.newKeySet();
        //查询区块中和系统地址相关的充值交易
        List<AccountTransaction> accountTransactions = transactionsList.parallelStream().map(transaction -> {
            //查询充值记录
            AccountTransaction accountTx = getAccountTx(transaction, height);
            //查询提现地址列表
            Address address = searchAddress(TronWalletApi.getFrom(transaction), getCurrency());
            //收集提现地址
            if (!ObjectUtils.isEmpty(address)) {
                relatedAddress.add(address.getAddress());
            }
            return accountTx;
        }).filter((acTx) -> {
            if (!ObjectUtils.isEmpty(acTx)) {
                //收集接收地址
                relatedAddress.add(acTx.getAddress());
                return true;
            }
            return false;
        }).collect(Collectors.toList());

        //更新币种余额
        updatePlatformWalletBalance(relatedAddress);
        return super.getTransactionDTOS(accountTransactions);
    }

    private void updatePlatformWalletBalance(Set<String> relatedAddress) {
        TronWallet selfReference = getSelf(getClass());
        selfReference.updateAddressBalance(relatedAddress);
    }

    private AccountTransaction getAccountTx(Protocol.Transaction transaction, Long height) {
        String txId = TronWalletApi.getTransactionHash(transaction);
        //更新已有的自己发出去的提现记录，更新状态为已确认
        updateWithdrawTXId(txId, getCurrency());
        String toAddress = TronWalletApi.getToAddress(transaction);
        if (StringUtils.isEmpty(toAddress)) {
            return null;
        }
        Address address = searchAddress(toAddress, getCurrency());
        if (ObjectUtils.isEmpty(address)) {
            return null;
        }
        if (checkInternalTransferTx(TronWalletApi.getFrom(transaction), txId)) {
            return null;
        }

        Long confirm = this.height - height + 1;
        Long amount = TronWalletApi.getAmount(transaction);
        if (amount == null || amount <= 0) {
            return null;
        }
        BigDecimal txValue = new BigDecimal(amount);
        AccountTransaction acTx = AccountTransaction.builder()
                .address(toAddress)
                .balance(txValue.divide(getDecimal()))
                .biz(address.getBiz())
                .txId(txId)
                .blockHeight(height)
                .confirmNum(confirm)
                .status((byte) Constants.WAITING)
                .currency(getCurrency().getIndex())
                .createDate(Date.from(Instant.now()))
                .updateDate(Date.from(Instant.now()))
                .build();
        if (toAddress.equals(withdrawAddress)) {
            acTx.setStatus((byte) Constants.CONFIRM);
        }
        return acTx;

    }

    @Override
    protected BigDecimal getBalance(String address, CurrencyEnum currency) {
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
