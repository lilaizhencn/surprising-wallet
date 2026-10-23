package com.surprising.wallet.common.signing;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable intent shared by the two UTXO signers; input ownership travels with each outpoint. */
public record UtxoSigningRequest(
        UUID tenantId, UUID requestId, Asset asset, Operation operation, String collectionNo,
        List<Input> inputs, List<Payment> payments, String changeAddress, long feeRate, long dustThreshold) {
    public UtxoSigningRequest {
        Objects.requireNonNull(tenantId, "signing tenant is required");
        Objects.requireNonNull(requestId, "signing request id is required");
        Objects.requireNonNull(asset, "signing asset is required");
        Objects.requireNonNull(operation, "signing operation is required");
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        payments = payments == null ? List.of() : List.copyOf(payments);
        if (operation == Operation.COLLECTION && (collectionNo == null || collectionNo.isBlank())) {
            throw new IllegalArgumentException("collection number is required");
        }
    }

    public enum Operation { WITHDRAWAL, COLLECTION }

    public record Asset(String chain, BitcoinLikeNetwork network, int currencyId, int decimals, int coinType) {
        public Asset {
            Objects.requireNonNull(chain, "signing chain is required");
            Objects.requireNonNull(network, "signing network is required");
            if (!ChainType.valueOf(chain).isUtxo()) throw new IllegalArgumentException("not a native UTXO chain");
            if (decimals < 0) throw new IllegalArgumentException("asset decimals cannot be negative");
        }
        public BigDecimal decimal() { return BigDecimal.TEN.pow(decimals); }
        public long atomic(BigDecimal amount) { return amount.multiply(decimal()).longValueExact(); }
        public AssetRuntimeMetadata metadata() {
            return AssetRuntimeMetadata.fromProfile(currencyId, chain, chain, 0, coinType, decimals, null);
        }
        public static Asset from(AssetRuntimeMetadata metadata, String network) {
            if (!metadata.chain().equals(metadata.assetSymbol())) throw new IllegalArgumentException("UTXO asset must be native");
            return new Asset(metadata.chain(), BitcoinLikeNetwork.parse(network), metadata.getIndex(),
                    metadata.getDecimals(), metadata.getBip44CoinType());
        }
    }

    public record Key(long userId, int biz, int index, String redeemScript) { }

    public record Input(String txId, int vout, String address, BigDecimal amount, Key key) {
        public Input {
            Objects.requireNonNull(txId, "input transaction id is required");
            Objects.requireNonNull(address, "input address is required");
            Objects.requireNonNull(amount, "input amount is required");
            Objects.requireNonNull(key, "input derivation is required");
        }
    }

    public record Payment(String reference, long userId, String address, BigDecimal amount, BigDecimal customerFee) {
        public Payment {
            Objects.requireNonNull(reference, "payment reference is required");
            Objects.requireNonNull(address, "payment address is required");
            Objects.requireNonNull(amount, "payment amount is required");
            customerFee = customerFee == null ? BigDecimal.ZERO : customerFee;
        }
    }

    public BigDecimal inputAmount() {
        return inputs.stream().map(Input::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public long inputAtomic() {
        long total = 0;
        for (Input input : inputs) total = Math.addExact(total, asset.atomic(input.amount()));
        return total;
    }

    public long paymentAtomic() {
        long total = 0;
        for (Payment payment : payments) total = Math.addExact(total, asset.atomic(payment.amount()));
        return total;
    }

    public void validateForSigning() {
        if (inputs.isEmpty() || payments.isEmpty()) throw new IllegalArgumentException("signing inputs and payments are required");
        for (Input input : inputs) {
            if (input.vout() < 0 || !input.txId().matches("[0-9a-fA-F]{64}") || asset.atomic(input.amount()) <= 0) {
                throw new IllegalArgumentException("invalid signing input");
            }
            Math.toIntExact(input.key().userId());
        }
        for (Payment payment : payments) {
            if (payment.address().isBlank() || asset.atomic(payment.amount()) <= 0 || payment.customerFee().signum() < 0) {
                throw new IllegalArgumentException("invalid signing payment");
            }
            asset.atomic(payment.customerFee());
        }
        inputAtomic();
        paymentAtomic();
    }

    public UtxoSigningRequest replacement(UUID nextRequest, long nextFeeRate, List<Payment> nextPayments) {
        return new UtxoSigningRequest(tenantId, nextRequest, asset, operation, collectionNo,
                inputs, nextPayments, changeAddress, nextFeeRate, dustThreshold);
    }
}
