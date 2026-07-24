package com.surprising.wallet.service.chain.tron;

import org.tron.trident.abi.datatypes.Address;
import org.tron.trident.abi.datatypes.Function;
import org.tron.trident.abi.datatypes.Type;
import org.tron.trident.abi.datatypes.generated.Uint256;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.proto.Chain;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Builds signed TRC20 transfer transactions through triggerSmartContract.
 * Token contract addresses and decimals must be loaded from token_config.
 */
@Service
public class TronTrc20Service {
    public TronTransactionService.SignedTronTransaction signTransfer(TronTridentClient client,
                                                                    KeyPair keyPair,
                                                                    String contractAddress,
                                                                    String recipientAddress,
                                                                    BigDecimal amount,
                                                                    int decimals,
                                                                    long feeLimitSun) {
        String ownerAddress = keyPair.toBase58CheckAddress();
        List<Type> inputs = List.of(
                new Address(TronAddressCodec.toAbiAddress(recipientAddress)),
                new Uint256(Trc20AbiCodec.toRawAmount(amount, decimals))
        );
        Function transfer = new Function("transfer", inputs, List.of());
        Chain.Transaction unsigned = client.api()
                .triggerCall(ownerAddress, contractAddress, transfer)
                .setFeeLimit(feeLimitSun)
                .build();
        Chain.Transaction signed = client.api().signTransaction(unsigned, keyPair);
        return new TronTransactionService.SignedTronTransaction(TronTransactionService.txId(signed), signed);
    }
    public String broadcast(TronTridentClient client, TronTransactionService.SignedTronTransaction transaction) {
        return client.broadcast(transaction.transaction());
    }
}
