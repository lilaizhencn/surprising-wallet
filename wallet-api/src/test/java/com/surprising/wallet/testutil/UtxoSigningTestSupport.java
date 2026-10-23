package com.surprising.wallet.testutil;

import com.surprising.wallet.chain.model.UtxoSigningTransaction;
import com.surprising.wallet.common.signing.UtxoPartialSignature;
import com.surprising.wallet.common.signing.UtxoSignedTransaction;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Typed fixtures for API state tests whose RPC boundary is mocked. Crypto tests use real signed transactions. */
public final class UtxoSigningTestSupport {
    private UtxoSigningTestSupport() { }

    public static UtxoSigningRequest request(UUID tenant, UtxoSigningRequest.Operation operation,
                                              String input, String payment) {
        boolean collection = operation == UtxoSigningRequest.Operation.COLLECTION;
        return new UtxoSigningRequest(tenant, UUID.randomUUID(),
                new UtxoSigningRequest.Asset("BTC", BitcoinLikeNetwork.REGTEST, 1, 8, 0), operation,
                collection ? "COLL-RBF" : null,
                List.of(new UtxoSigningRequest.Input("c".repeat(64), 0, "source", new BigDecimal(input),
                        new UtxoSigningRequest.Key(100, 0, 0, null))),
                List.of(new UtxoSigningRequest.Payment(collection ? "COLL-RBF" : "WD-1", 100,
                        collection ? "collector" : "external", new BigDecimal(payment), BigDecimal.ZERO)),
                "collector", 10, 546);
    }

    public static UtxoSignedTransaction signed(String hash, long fee) {
        return new UtxoSignedTransaction("00", hash, fee, 636, 159, new UtxoPartialSignature.Estimates(0, 159, 636));
    }

    public static UtxoSigningTransaction row(int id, UtxoSigningRequest request, short status,
                                              UtxoSignedTransaction signed) {
        return new UtxoSigningTransaction(id, request, signed, List.of(), status, null, null, Instant.EPOCH, Instant.EPOCH);
    }
}
