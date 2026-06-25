package com.surprising.wallet.sig.first.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.LegacyMultisigTransactionBuilder;
import com.surprising.wallet.sdk.bitcoinj.core.P2shMultisigFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinNetworkParameters;
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

/**
 * First signer for Dogecoin legacy P2SH 2-of-3 transactions.
 */
@Component
public class DogeFirstSignService implements ISignService {
    @Autowired
    private PubKeyConfig pubKeyConfig;

    @Value("${sw.wallet.masterKey}")
    private String masterKey;

    @Value("${sw.doge.network:testnet}")
    private String network;

    private Bip32Node root;

    @PostConstruct
    public void init() {
        root = Bip32Node.decode(masterKey);
    }

    @Override
    public void signTransaction(WithdrawTransaction transaction) {
        RuntimeAsset currency = RuntimeAsset.fromTransaction(transaction);
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());
        try {
            List<UtxoTransaction> utxos = signature.getJSONArray("utxos")
                    .toJavaList(UtxoTransaction.class);
            List<Address> addresses = signature.getJSONArray("addresses")
                    .toJavaList(Address.class);
            List<WithdrawRecord> records = signature.getJSONArray("withdraw")
                    .toJavaList(WithdrawRecord.class);
            BigDecimal decimal = currency.getDecimal();
            LegacyMultisigTransactionBuilder builder =
                    new LegacyMultisigTransactionBuilder(networkParameters());
            List<ECKey> signingKeys = new ArrayList<>(utxos.size());
            List<String> redeemScripts = new ArrayList<>(utxos.size());
            for (int i = 0; i < utxos.size(); i++) {
                Address address = addresses.get(i);
                UtxoTransaction utxo = utxos.get(i);
                String redeemScript = pubKeyConfig.genRedeemScript(address, currency);
                redeemScripts.add(redeemScript);
                signingKeys.add(derive(address, currency).getEcKey());
                builder.addInput(
                        utxo.getTxId(), utxo.getSeq(), redeemScript,
                        Coin.valueOf(utxo.getBalance().multiply(decimal).longValueExact()));
            }

            long inputKoinu = transaction.getBalance().multiply(decimal).longValueExact();
            long sentKoinu = records.stream()
                    .map(WithdrawRecord::getBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .multiply(decimal).longValueExact();
            long feeRate = signature.getLongValue("feeRate");
            if (feeRate <= 0) {
                feeRate = DogecoinFeePolicy.DEFAULT_FEE_RATE_KOINU_PER_BYTE;
            }
            long estimatedBytes = P2shMultisigFeeCalculator.estimateBytes(
                    utxos.size(), records.size(), 2, 3);
            long fee = DogecoinFeePolicy.feeForBytes(estimatedBytes, feeRate);
            long change = inputKoinu - sentKoinu - fee;
            long dust = DogecoinFeePolicy.RECOMMENDED_DUST_THRESHOLD_KOINU;
            if (change >= dust) {
                long withChangeBytes = P2shMultisigFeeCalculator.estimateBytes(
                        utxos.size(), records.size() + 1, 2, 3);
                long withChangeFee = DogecoinFeePolicy.feeForBytes(withChangeBytes, feeRate);
                long withChange = inputKoinu - sentKoinu - withChangeFee;
                if (withChange >= dust) {
                    estimatedBytes = withChangeBytes;
                    fee = withChangeFee;
                    change = withChange;
                } else {
                    fee = inputKoinu - sentKoinu;
                    change = 0;
                }
            } else if (change >= 0) {
                fee = inputKoinu - sentKoinu;
                change = 0;
            }
            if (change < 0) {
                throw new IllegalArgumentException("insufficient DOGE input for network fee");
            }

            for (WithdrawRecord record : records) {
                long value = record.getBalance().multiply(decimal).longValueExact();
                if (value < dust) {
                    throw new IllegalArgumentException("DOGE output below recommended dust: " + value);
                }
                builder.addOutput(record.getAddress(), Coin.valueOf(value));
            }
            if (change > 0) {
                builder.addOutput(signature.getString("changeAddress"), Coin.valueOf(change));
            }

            String firstSigned = builder.buildFirstSign(signingKeys);
            signature.put("firstSignTx", firstSigned);
            signature.put("scriptType", "p2sh");
            signature.put("fee", fee);
            signature.put("estimatedBytes", estimatedBytes);
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

    @Override
    public String chain() {
        return "DOGE";
    }

    private Bip32Node derive(Address address, RuntimeAsset currency) {
        return root.getChild(44)
                .getChild(currency.getBip44CoinType())
                .getChild(address.getBiz())
                .getChild(address.getUserId().intValue())
                .getChild(address.getIndex());
    }

    private DogecoinNetworkParameters networkParameters() {
        if ("main".equalsIgnoreCase(network) || "mainnet".equalsIgnoreCase(network)) {
            return DogecoinNetworkParameters.mainnet();
        }
        return "regtest".equalsIgnoreCase(network)
                ? DogecoinNetworkParameters.regtest()
                : DogecoinNetworkParameters.testnet();
    }
}
