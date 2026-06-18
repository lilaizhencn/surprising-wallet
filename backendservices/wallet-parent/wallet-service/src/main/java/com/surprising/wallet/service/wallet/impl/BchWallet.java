package com.surprising.wallet.service.wallet.impl;

import com.github.kiulian.converter.AddressConverter;
import com.surprising.wallet.client.command.BchCommand;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.rpc.BtcLikeRawTransaction;
import com.surprising.wallet.common.pojo.rpc.ScriptPubKey;
import com.surprising.wallet.common.pojo.rpc.TxOutput;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.wallet.AbstractBtcLikeWallet;
import com.surprising.wallet.service.wallet.IWallet;
import lombok.extern.slf4j.Slf4j;
import org.bitcoincashj.core.NetworkParameters;
import org.bitcoincashj.core.ScriptException;
import org.bitcoincashj.core.Transaction;
import org.bitcoincashj.core.Utils;
import org.bitcoincashj.params.MainNetParams;
import org.bitcoincashj.params.TestNet3Params;
import org.bitcoincashj.script.Script;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * @author lilaizhen
 * @data 09/04/2018
 */
@Slf4j
@Component
public class BchWallet extends AbstractBtcLikeWallet implements IWallet {
    @Autowired
    BchCommand bchCommand;

    @PostConstruct
    public void init() {
        super.setCommand(bchCommand);
    }

    @Override
    public CurrencyEnum getCurrency() {
        return CurrencyEnum.BCH;
    }

    public NetworkParameters getBchNetworkParameters() {
        if (CONSTANT.NETWORK.equals("test")) {
            return TestNet3Params.get();
        }

        return MainNetParams.get();
    }

    @Override
    public org.bitcoinj.core.NetworkParameters getNetworkParameters() {
        return org.bitcoinj.params.MainNetParams.get();
        //throw new RuntimeException("Bch not support this method");
    }

    public List<TransactionDTO> convertFromBchBjTx(Transaction transaction) {
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
                String addr = script.getToAddress(getBchNetworkParameters()).toBase58();
                addresses.add(addr);
            } catch (ScriptException e) {
                BchWallet.log.error("ScriptException error, txid:{}", txid);
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
                String address = AddressConverter.toLegacyAddress(addressStr);
                if (!ObjectUtils.isEmpty(address)) {
                    valid = true;
                }
            } catch (Throwable e) {
                BchWallet.log.error("{} is not valid", addressStr, e);
            }
        }
        return valid;

    }


}
