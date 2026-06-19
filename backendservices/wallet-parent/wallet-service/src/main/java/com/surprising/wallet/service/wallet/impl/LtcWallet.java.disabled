package com.surprising.wallet.service.wallet.impl;

import com.surprising.wallet.client.command.LtcCommand;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.rpc.BtcLikeRawTransaction;
import com.surprising.wallet.common.pojo.rpc.ScriptPubKey;
import com.surprising.wallet.common.pojo.rpc.TxOutput;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.header.LiteMainNetParam;
import com.surprising.wallet.service.wallet.AbstractBtcLikeWallet;
import com.surprising.wallet.service.wallet.IWallet;
import lombok.extern.slf4j.Slf4j;
import org.litecoinj.core.Address;
import org.litecoinj.core.NetworkParameters;
import org.litecoinj.core.Transaction;
import org.litecoinj.core.Utils;
import org.litecoinj.params.LitecoinMainNetParams;
import org.litecoinj.script.Script;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * @author atomex-team
 * @data 09/04/2018
 */
@Slf4j
@Component
public class LtcWallet extends AbstractBtcLikeWallet implements IWallet {
    @Autowired
    LtcCommand command;

    public static void main(String[] args) {

        Address address = Address.fromBase58(LitecoinMainNetParams.get(), "3NqPQz2gZnxFXwsG4t3fDDpdZ67p8Qvw9L");
        System.out.println(address);

        Script script = new Script(Utils.HEX.decode("a914e7efe88c8e6f666a54227bee620d457736a1ead887"));
        String addr = script.getToAddress(LitecoinMainNetParams.get()).toString();
        System.out.println(addr);

        LtcWallet wallet = new LtcWallet();
        boolean valid = wallet.checkAddress("3BK65998JEEtmNKfbzDDZcEQUji6Bf76b8");
        System.out.println(valid);
    }

    @PostConstruct
    public void init() {
        super.setCommand(command);
    }

    @Override
    public CurrencyEnum getCurrency() {
        return CurrencyEnum.LTC;
    }

    public NetworkParameters getLiteNetworkParameters() {
        return LitecoinMainNetParams.get();
    }

    @Override
    public org.bitcoinj.core.NetworkParameters getNetworkParameters() {
        return LiteMainNetParam.get();
        //throw new RuntimeException("Ltc not support this method");
    }

    public List<TransactionDTO> convertFromLtcBjTx(Transaction transaction) {
        byte[] data = transaction.bitcoinSerialize();
        org.bitcoinj.core.Transaction btcTx = new org.bitcoinj.core.Transaction(Constants.NET_PARAMS, data);
        return convertFromBjTx(btcTx, 0L);
    }

    @Override
    public BtcLikeRawTransaction getRawTransaction(String txid) {
        BtcLikeRawTransaction rawTx = command.getRawTransaction(txid, true);
        List<TxOutput> outs = rawTx.getVout();
        outs.parallelStream().forEach((out) -> {
            try {
                ScriptPubKey pubKey = out.getScriptPubKey();
                List<String> addresses = pubKey.getAddresses();
                if (CollectionUtils.isEmpty(addresses)) {
                    return;
                }
                addresses.clear();
                Script script = new Script(Utils.HEX.decode(out.getScriptPubKey().getHex()));
                String addr = script.getToAddress(getLiteNetworkParameters()).toString();
                addresses.add(addr);
            } catch (Throwable e) {
                log.error("ScriptException error, txid:{}", txid);
            }

        });
        return rawTx;
        //final Transaction transaction = new Transaction(Constants.NET_PARAMS, Utils.HEX.decode(rawTx));
        //return transaction;
        //return this.command.decodeRawTransactionStr(rawTx);
    }


    @Override
    public boolean checkAddress(String addressStr) {
        boolean valid = false;
        if (StringUtils.hasText(addressStr)) {
            try {
                Address address = Address.fromBase58(getLiteNetworkParameters(), addressStr);
                if (!ObjectUtils.isEmpty(address)) {
                    valid = true;
                }
            } catch (Throwable e) {
                LtcWallet.log.error("{} is not valid", addressStr, e);
            }
        }
        return valid;

    }

}
