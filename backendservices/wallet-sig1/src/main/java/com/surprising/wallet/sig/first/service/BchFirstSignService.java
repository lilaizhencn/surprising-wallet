package com.surprising.wallet.sig.first.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashMultisigTransactionBuilder;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashNetworkParameters;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import jakarta.annotation.PostConstruct;
import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class BchFirstSignService implements ISignService {
    @Autowired
    private PubKeyConfig pubKeyConfig;

    @Value("${atomex.wallet.masterKey}")
    private String masterKey;

    @Value("${atomex.bch.network:testnet}")
    private String network;

    private Bip32Node root;

    @PostConstruct
    public void init() {
        root = Bip32Node.decode(masterKey);
    }

    @Override
    public CurrencyEnum getCurrency() {
        return CurrencyEnum.BCH;
    }

    @Override
    public void signTransaction(WithdrawTransaction transaction) {
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());
        try {
            List<UtxoTransaction> utxos =
                    signature.getJSONArray("utxos").toJavaList(UtxoTransaction.class);
            List<Address> addresses =
                    signature.getJSONArray("addresses").toJavaList(Address.class);
            List<WithdrawRecord> withdrawals =
                    signature.getJSONArray("withdraw").toJavaList(WithdrawRecord.class);
            if (utxos.size() != addresses.size()) {
                throw new IllegalArgumentException("BCH UTXO/address count mismatch");
            }
            BitcoinCashMultisigTransactionBuilder builder =
                    new BitcoinCashMultisigTransactionBuilder(networkParameters());
            List<ECKey> keys = new ArrayList<>();
            List<String> redeemScripts = new ArrayList<>();
            BigDecimal decimal = CurrencyEnum.BCH.getDecimal();
            for (int i = 0; i < utxos.size(); i++) {
                Address address = addresses.get(i);
                String derivedRedeemScript = pubKeyConfig.genRedeemScript(address);
                if (address.getRedeemScript() != null
                        && !address.getRedeemScript().isBlank()
                        && !derivedRedeemScript.equalsIgnoreCase(address.getRedeemScript())) {
                    throw new IllegalArgumentException(
                            "stored redeemScript does not match derived BCH keys");
                }
                redeemScripts.add(derivedRedeemScript);
                keys.add(derive(address).getEcKey());
                UtxoTransaction utxo = utxos.get(i);
                builder.addInput(
                        utxo.getTxId(),
                        utxo.getSeq(),
                        derivedRedeemScript,
                        Coin.valueOf(utxo.getBalance().multiply(decimal).longValueExact()));
            }
            long total = transaction.getBalance().multiply(decimal).longValueExact();
            long sent = withdrawals.stream()
                    .map(WithdrawRecord::getBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .multiply(decimal)
                    .longValueExact();
            long feeRate = signature.getLongValue("feeRate");
            if (feeRate <= 0) {
                feeRate = BitcoinCashFeePolicy.DEFAULT_SAT_PER_BYTE;
            }
            long dust = signature.getLongValue("dustThreshold");
            if (dust <= 0) {
                dust = BitcoinCashFeePolicy.DUST_THRESHOLD_SAT;
            }
            BitcoinCashFeePolicy.SpendPlan spendPlan =
                    BitcoinCashFeePolicy.calculateSpendPlan(
                            total, sent, utxos.size(), withdrawals.size(), feeRate, dust);
            for (WithdrawRecord withdrawal : withdrawals) {
                long value = withdrawal.getBalance().multiply(decimal).longValueExact();
                if (value < dust) {
                    throw new IllegalArgumentException("BCH dust");
                }
                builder.addOutput(withdrawal.getAddress(), Coin.valueOf(value));
            }
            if (spendPlan.change() > 0) {
                String changeAddress = signature.getString("changeAddress");
                if (changeAddress == null || changeAddress.isBlank()) {
                    throw new IllegalArgumentException("missing BCH change address");
                }
                builder.addOutput(changeAddress, Coin.valueOf(spendPlan.change()));
            }
            signature.put("firstSignTx", builder.buildFirstSign(keys));
            signature.put("scriptType", "bch-p2sh");
            signature.put("fee", spendPlan.fee());
            signature.put("estimatedBytes", spendPlan.estimatedBytes());
            signature.put("valid", true);
            JSONArray scripts = new JSONArray();
            redeemScripts.forEach(scripts::add);
            signature.put("redeemScripts", scripts);
        } catch (Throwable error) {
            signature.put("valid", false);
            signature.put("error", error.getMessage());
        }
        transaction.setSignature(signature.toJSONString());
    }

    private Bip32Node derive(Address address) {
        return root.getChild(44)
                .getChild(145)
                .getChild(address.getBiz())
                .getChild(address.getUserId().intValue())
                .getChild(address.getIndex());
    }

    private BitcoinCashNetworkParameters networkParameters() {
        if ("main".equalsIgnoreCase(network) || "mainnet".equalsIgnoreCase(network)) {
            return BitcoinCashNetworkParameters.mainnet();
        }
        return "regtest".equalsIgnoreCase(network)
                ? BitcoinCashNetworkParameters.regtest()
                : BitcoinCashNetworkParameters.testnet();
    }
}
