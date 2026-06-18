package com.surprising.wallet.jobs.transfer;

import com.alibaba.fastjson.JSONObject;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.service.AddressService;
import com.surprising.wallet.service.service.WithdrawTransactionService;
import com.surprising.wallet.service.wallet.AbstractEthLikeWallet;
import com.surprising.wallet.service.wallet.WalletContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.math.BigDecimal;
import java.util.LinkedList;
import java.util.List;

/**
 * 处理用户充错币种
 *
 * @author atomex
 */
@Component
@Slf4j
public class TransferBetweenCurrencyJob {

    @Autowired
    private AddressService addressService;

    @Autowired
    private WithdrawTransactionService transactionService;
    @Autowired
    private WalletContext walletContext;

    //    @Scheduled(cron = "1 1/5 * * * ?")
    public void execute() {
        log.info("处理用户充错币种 开始");
        String key = Constants.WALLET_TRANSFER_BETWEEN_CURRENCY_KEY;
        try {
            while (true) {
                String recordStr = REDIS.rPop(key);
                if (ObjectUtils.isEmpty(recordStr)) {
                    break;
                }
                JSONObject recordJson = JSONObject.parseObject(recordStr);
                String srcCur = recordJson.getString("srcCurrency");

                if (CurrencyEnum.parseName(srcCur) == CurrencyEnum.ETH || CurrencyEnum.parseName(srcCur) == CurrencyEnum.ETC) {
                    transferBetweenEtcAndEth(recordJson);
                } else {
                    transferBetweenUtxo(recordJson);
                }
            }
        } catch (Throwable e) {
            log.error("处理用户充错币种 异常", e);
        }
        log.info("处理用户充错币种 结束");
    }

    private void transferBetweenUtxo(JSONObject recordJson) {

        String dstCurStr = recordJson.getString("dstCurrency");
        String dstAddrStr = recordJson.getString("dstAddress");
        BigDecimal transferAmount = recordJson.getBigDecimal("transferAmount");
        BigDecimal amount = recordJson.getBigDecimal("amount");

        ShardTable addressTable = ShardTable.builder().prefix(dstCurStr).build();
        List<Address> addresses = new LinkedList<>();
        Address address = addressService.getAddress(dstAddrStr, addressTable);
        addresses.add(address);
        String[] txIdAndSqe = recordJson.getString("txId").split("-");
        LinkedList<UtxoTransaction> utxos = new LinkedList<>();
        UtxoTransaction utxo = UtxoTransaction.builder()
                .balance(amount)
                .txId(txIdAndSqe[0])
                .seq(Short.parseShort(txIdAndSqe[1]))
                .build();
        utxos.add(utxo);

        CurrencyEnum srcCurrency = CurrencyEnum.parseName(recordJson.getString("srcCurrency"));
        String srcAddrStr = recordJson.getString("srcAddress");
        String changeAddr = recordJson.getString("changeAddrStr");
        int feePerKb = REDIS.getInt(Constants.WALLET_FEE + srcCurrency.getIndex());

        JSONObject signature = new JSONObject();
        //final IWallet wallet = this.walletContext.getWallet(srcCurrency);
        //final Address changeAddress = wallet.genNewAddress(Constants.USER_ID, Constants.BIZ);
        List<WithdrawRecord> records = new LinkedList<>();
        WithdrawRecord record = WithdrawRecord.builder()
                .address(srcAddrStr)
                .balance(transferAmount)
                .fee(BigDecimal.ZERO)
                .build();
        records.add(record);
        signature.put("utxos", utxos);
        signature.put("addresses", addresses);
        signature.put("withdraw", records);
        signature.put("changeAddress", changeAddr);
        signature.put("feePerKb", feePerKb);

        WithdrawTransaction transaction = WithdrawTransaction.builder()
                .balance(amount)
                .currency(srcCurrency.getIndex())
                .status(Constants.SIGNING)
                .txId("singing")
                .signature(signature.toJSONString())
                .build();
        ShardTable table = ShardTable.builder().prefix(srcCurrency.getName()).build();

        transactionService.add(transaction, table);
        String val = JSONObject.toJSONString(transaction);
        REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_FIRST_KEY, val);

    }

    private void transferBetweenEtcAndEth(JSONObject recordJson) {
        String dstCurStr = recordJson.getString("dstCurrency");
        String dstAddrStr = recordJson.getString("dstAddress");
        BigDecimal amount = recordJson.getBigDecimal("amount");
        ShardTable addressTable = ShardTable.builder().prefix(dstCurStr).build();
        Address address = addressService.getAddress(dstAddrStr, addressTable);

        CurrencyEnum srcCurrency = CurrencyEnum.parseName(recordJson.getString("srcCurrency"));
        String srcAddrStr = recordJson.getString("srcAddress");

        AbstractEthLikeWallet wallet = (AbstractEthLikeWallet) walletContext.getWallet(srcCurrency);
        Long nonce = wallet.getAddressNonce(dstAddrStr);
        address.setNonce(nonce.intValue());
        BigDecimal gas = new BigDecimal("0.00000000000042");
        BigDecimal gasPrice = new BigDecimal("0.00000002");
        JSONObject signature = new JSONObject();

        signature.put("address", address);
        signature.put("from", dstAddrStr);
        signature.put("to", srcAddrStr);
        signature.put("value", amount);
        signature.put("gas", gas);
        signature.put("nonce", nonce);
        signature.put("gasPrice", gasPrice);

        WithdrawTransaction transaction = WithdrawTransaction.builder()
                .balance(amount)
                .currency(srcCurrency.getIndex())
                .status(Constants.SIGNING)
                .txId("transferBetweenCurrency")
                .signature(signature.toJSONString())
                .build();
        ShardTable table = ShardTable.builder().prefix(srcCurrency.getName()).build();
        transactionService.add(transaction, table);
        String val = JSONObject.toJSONString(transaction);
        REDIS.lPush(Constants.WALLET_WITHDRAW_SIG_SECOND_KEY, val);

    }

}
