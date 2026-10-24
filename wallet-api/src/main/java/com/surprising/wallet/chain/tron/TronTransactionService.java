package com.surprising.wallet.chain.tron;

import org.tron.trident.core.ApiWrapper;
import org.tron.trident.core.exceptions.IllegalException;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.proto.Chain;
import org.springframework.stereotype.Service;

@Service
public class TronTransactionService {
    public SignedTronTransaction signTrxTransfer(TronTridentClient client, KeyPair keyPair,
                                                 String toAddress, long amountSun) throws IllegalException {
        String fromAddress = keyPair.toBase58CheckAddress();
        Chain.Transaction tx = client.createTrxTransfer(fromAddress, toAddress, amountSun);
        Chain.Transaction signed = client.signTransaction(tx, keyPair);
        return new SignedTronTransaction(txId(signed), signed);
    }
    public static String txId(Chain.Transaction transaction) {
        return ApiWrapper.toHex(ApiWrapper.calculateTransactionHash(transaction));
    }
    public record SignedTronTransaction(String txId, Chain.Transaction transaction) {
    }
}
