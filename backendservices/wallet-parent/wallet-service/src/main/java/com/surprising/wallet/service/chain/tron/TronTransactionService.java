package com.surprising.wallet.service.chain.tron;

import org.tron.trident.core.ApiWrapper;
import org.tron.trident.core.exceptions.IllegalException;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Response;
import org.springframework.stereotype.Service;

/**
 * Builds and signs native TRX transfer transactions with Trident.
 * The KeyPair must be created from the existing Bitcoin ECKey-derived private key.
 */
@Service
public class TronTransactionService {
    public SignedTronTransaction signTrxTransfer(TronTridentClient client, KeyPair keyPair,
                                                 String toAddress, long amountSun) throws IllegalException {
        String fromAddress = keyPair.toBase58CheckAddress();
        Response.TransactionExtention tx = client.api().transfer(fromAddress, toAddress, amountSun);
        Chain.Transaction signed = client.api().signTransaction(tx, keyPair);
        return new SignedTronTransaction(txId(signed), signed);
    }

    public String broadcast(TronTridentClient client, SignedTronTransaction transaction) {
        return client.broadcast(transaction.transaction());
    }

    public static String txId(Chain.Transaction transaction) {
        return ApiWrapper.toHex(ApiWrapper.calculateTransactionHash(transaction));
    }

    public record SignedTronTransaction(String txId, Chain.Transaction transaction) {
    }
}
