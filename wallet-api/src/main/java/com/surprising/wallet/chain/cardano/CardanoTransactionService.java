package com.surprising.wallet.chain.cardano;

import com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec;

import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.signing.CardanoSigningPayload;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.service.CardanoSubmissionService.Kind;
import com.surprising.wallet.service.CardanoSubmissionService.Operation;
import com.surprising.wallet.service.CardanoSubmissionService.Payment;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.NonNull;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import utxorpc.v1beta.query.Query;

/** Public native RPC and external MPC; only the original body/witness may be recovered or broadcast. */
@Service
@RequiredArgsConstructor
public class CardanoTransactionService {
    private final CardanoRpcClient rpc;
    private final CardanoSubmissionService submissions;
    private final ChainProfileRepository profiles;
    private final TokenConfigRepository tokens;
    private final ChainAddressRepository addresses;
    private final CollectionRecordRepository collections;
    private final CustodyGasService gas;
    @NonNull private final WalletRuntimeConfigService runtime;
    private final ObjectMapper json;
    private final MpcAuthorizationService authorizations;
    private static final HexFormat HEX = HexFormat.of();

    public String send(Operation op, ChainAddressRecord source) {
        require(op.kind() == Kind.WITHDRAWAL, "Cardano withdrawal operation required");
        finishExistingFunding(op);
        if (submissions.intent(op).isPresent()) return recoverOriginal(op);
        runtime.requireTaskEnabled("ADA", WalletRuntimeConfigService.TASK_WITHDRAW, "Cardano withdrawal");
        return transfer(op, source, submissions.payment(op));
    }

    public String collect(UUID tenant, String reference, ChainAddressRecord source) {
        var op = new Operation(tenant, reference, Kind.COLLECTION);
        finishExistingFunding(op);
        if (submissions.intent(op).isPresent()) return recoverOriginal(op);
        runtime.requireTaskEnabled("ADA", WalletRuntimeConfigService.TASK_COLLECTION, "Cardano collection");
        require(collections.claimSigningDurable(tenant, "ADA", reference) == 1, "Cardano collection is claimed; recovery owns its intent");
        try {
            String hash = transfer(op, source, submissions.payment(op)); submissions.restoreSent(op, hash); return hash;
        } catch (RuntimeException error) { submissions.unknown(op, error.getClass().getSimpleName()); throw error; }
    }

    private String transfer(Operation op, ChainAddressRecord source, Payment payment) {
        var request = submissions.reserve(op, source, prepare(op, source, payment, true));
        submissions.sign(op, request.id());
        return broadcast(op, submissions.commit(op, request.id()), submissions.payload(request));
    }

    private CardanoSigningPayload prepare(Operation op, ChainAddressRecord source, Payment payment, boolean allowFunding) {
        for (int attempt = 0; attempt < 2; attempt++) {
            var profile = profiles.findAccountByChain("ADA").orElseThrow();
            if(op.kind()==Kind.COLLECTION && attempt==0)authorizations.planCollection(new com.surprising.wallet.common.approval.AutomaticParentPlan(op.tenantId(),source.getMpcKeyId(),"ADA",profile.getNetwork(),payment.symbol(),op.reference(),payment.recipient(),payment.amount(),null));
            int confirmations = Math.max(1, profile.getWithdrawConfirmations());
            // Capture the confirmed search origin before reading the live input snapshot. A tip reorg must not lose the search origin.
            var origin = rpc.confirmedBlock(confirmations);
            var genesis = rpc.genesis(); var parameters = rpc.parameters();
            var protocol = CardanoPublicTransactionBuilder.protocol(parameters);
            var snapshot = rpc.utxos(source.getAddress());
            var amounts = balances(snapshot);
            int decimals = 6; String unit = "lovelace";
            if (!"ADA".equals(payment.symbol())) {
                var token = tokens.findDefinition("ADA", profile.getNetwork(), payment.symbol()).orElseThrow();
                decimals = token.getDecimals(); unit = MpcCardanoTransactionCodec.assetUnit(token.getContractAddress());
            }
            BigInteger principal = payment.amount().movePointRight(decimals).toBigIntegerExact();
            require(amounts.getOrDefault(unit, BigInteger.ZERO).compareTo(principal) >= 0, "Cardano principal exceeds on-chain balance");
            var remaining = new TreeMap<>(amounts); remaining.remove("lovelace");
            if (!"lovelace".equals(unit)) remaining.compute(unit, (ignored, value) -> value.subtract(principal));
            remaining.values().removeIf(value -> value.signum() == 0);
            BigInteger recipientMinimum = minimum(protocol, payment.recipient(), "lovelace".equals(unit) ? Map.of() : Map.of(unit, principal));
            if ("lovelace".equals(unit)) require(principal.compareTo(recipientMinimum) >= 0, "Cardano native principal is below minimum output ADA");
            BigInteger required = ("lovelace".equals(unit) ? principal : recipientMinimum)
                    .add(minimum(protocol, source.getAddress(), remaining)).add(feeBudget(protocol));
            BigInteger available = amounts.getOrDefault("lovelace", BigInteger.ZERO);
            if (available.compareTo(required) < 0) {
                require(allowFunding && op.kind() != Kind.FUNDING, "Cardano Gas signer cannot cover principal, change minimum and fee");
                fund(op, source, required.subtract(available).max(minimum(protocol, source.getAddress(), Map.of())));
                continue;
            }
            var prepared = CardanoPublicTransactionBuilder.prepare(genesis, parameters, snapshot, HEX.parseHex(submissions.publicKey(source)),
                    source.getAddress(), payment.recipient(), unit, principal, 720);
            return new CardanoSigningPayload(payment.symbol(), payment.amount(), payment.customerFee(), decimals, confirmations,
                    CardanoRpcClient.point(origin.reference()), prepared.unsigned());
        }
        throw new IllegalStateException("Cardano original Gas funding did not cover the required native budget");
    }

    private void fund(Operation business, ChainAddressRecord target, BigInteger amount) {
        var op = funding(business);
        require(submissions.intent(op).isEmpty(), "Cardano original Gas funding is already fixed; reconcile its outcome before another allocation");
        var account = gas.findGasAccount(business.tenantId(), "ADA").filter(row -> "ACTIVE".equals(row.status())).orElseThrow();
        var source = addresses.findEnabledByTenantAndAddress(business.tenantId(), "ADA", account.address()).orElseThrow();
        require(source.getMpcKeyId() != null && !source.getAddress().equals(target.getAddress()), "Cardano Gas signer must be a different owned joint address");
        var payment = new Payment("ADA", target.getAddress(), fromLovelace(amount), BigDecimal.ZERO);
        submissions.reserve(op, source, prepare(op, source, payment, false)); finishFunding(op);
    }

    private void finishExistingFunding(Operation op) { var funding = funding(op); if (submissions.intent(funding).isPresent()) finishFunding(funding); }
    private void finishFunding(Operation op) {
        if (!submissions.terminal(op)) {
            recoverOriginal(op);
            for (int attempt = 0; attempt < 20 && !submissions.terminal(op); attempt++) {
                confirm(op);
                if (!submissions.terminal(op)) try { Thread.sleep(1000); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Cardano funding interrupted", error); }
            }
        }
        require(submissions.terminal(op), "Cardano Gas funding is pending; retry retains its original body");
        require(submissions.paid(op), "Cardano original Gas funding failed; review its proven result");
    }

    public boolean recover(Operation op) {
        finishExistingFunding(op);
        if (submissions.intent(op).isEmpty()) return false;
        recoverOriginal(op); return true;
    }
    private String recoverOriginal(Operation op) {
        var request = submissions.intent(op).orElseThrow();
        submissions.sign(op, request.id()); var selected = submissions.commit(op, request.id());
        // A durable raw with unknown delivery is monitored even if the retry reply is lost again.
        submissions.restoreSent(op, selected.txHash());
        return broadcast(op, selected, submissions.payload(request));
    }
    public int releaseUnsigned(Operation op) { return submissions.releaseUnsigned(op); }

    private String broadcast(Operation op, MpcCardanoTransactionCodec.Signed selected, CardanoSigningPayload payload) {
        if (submissions.terminal(op)) return selected.txHash();
        requireIdentity(payload);
        if (rpc.tip().getSlot() < MpcCardanoTransactionCodec.expiry(payload.unsigned())) {
            require(selected.txHash().equals(rpc.submit(HEX.parseHex(selected.transactionHex()))), "Cardano native submit returned a different body hash");
            submissions.observed(op, selected.txHash());
        }
        // Closed TTL is not a submission error or a fee receipt. Confirmation must search the fixed interval and check inputs.
        return selected.txHash();
    }

    public boolean confirmWithdrawal(UUID tenant, String reference) { return confirm(new Operation(tenant, reference, Kind.WITHDRAWAL)); }
    public boolean confirmCollection(UUID tenant, String reference) { return confirm(new Operation(tenant, reference, Kind.COLLECTION)); }
    private boolean confirm(Operation op) {
        if (submissions.terminal(op)) return false;
        var request = submissions.intent(op).orElseThrow(); var payload = submissions.payload(request); requireIdentity(payload);
        var selected = submissions.commit(op, request.id());
        var state = submissions.stored(op).orElseThrow(); var current = state.searched();
        var tip = rpc.tip(); long safe = tip.getHeight() - payload.requiredConfirmations() + 1;
        if (safe > current.height()) {
            var batch = rpc.blocksAfter(CardanoRpcClient.reference(current), safe, 200);
            var start = current;
            for (var original : batch) {
                var block = CardanoExecutionReceipt.verifyBlock(original);
                require(block.reference().getHeight() == current.height() + 1 && Objects.equals(block.parentHash(), current.hash()), "Cardano canonical proof search has a broken parent link");
                if (block.transactions().stream().anyMatch(tx -> tx.hash().equals(selected.txHash()))) {
                    var proof = json.createObjectNode().put("kind", "INCLUDED").put("genesisHash", payload.unsigned().genesisHash())
                            .put("blockBase64", Base64.getEncoder().encodeToString(original.toByteArray()));
                    return submissions.settle(op, proof, tip.getHeight());
                }
                current = CardanoRpcClient.point(block.reference());
            }
            if (!current.equals(start) && !submissions.searched(op, start, current)) return submissions.terminal(op);
        }
        if (current.height() >= safe && current.slot() >= MpcCardanoTransactionCodec.expiry(payload.unsigned())) {
            var refs = payload.unsigned().inputs().stream().map(input -> Query.TxoRef.newBuilder()
                    .setHash(com.google.protobuf.ByteString.copyFrom(HEX.parseHex(input.transactionId()))).setIndex(input.index()).build()).toList();
            var unspent = rpc.readUtxos(refs); var original = rpc.block(CardanoRpcClient.reference(current));
            var proof = json.createObjectNode().put("kind", "EXPIRED_UNSPENT").put("genesisHash", payload.unsigned().genesisHash())
                    .put("blockBase64", Base64.getEncoder().encodeToString(original.toByteArray()))
                    .put("unspentBase64", Base64.getEncoder().encodeToString(unspent.toByteArray()));
            return submissions.settle(op, proof, tip.getHeight());
        }
        if (tip.getSlot() < MpcCardanoTransactionCodec.expiry(payload.unsigned())) {
            // Identical replay can be rejected for a pending or just-mined input. Neither rejection nor timeout settles funds.
            try {
                if (selected.txHash().equals(rpc.submit(HEX.parseHex(selected.transactionHex())))) submissions.observed(op, selected.txHash());
            } catch (RuntimeException pending) { return false; }
        }
        return false;
    }

    public BigDecimal collectableNativeAmount(String address, BigDecimal candidate, BigDecimal reserve) {
        var protocol = CardanoPublicTransactionBuilder.protocol(rpc.parameters()); var available = balances(rpc.utxos(address));
        var assets = new TreeMap<>(available); assets.remove("lovelace");
        BigInteger fee = feeBudget(protocol).max(reserve.movePointRight(6).setScale(0, RoundingMode.CEILING).toBigIntegerExact());
        BigInteger limit = available.getOrDefault("lovelace", BigInteger.ZERO).subtract(minimum(protocol, address, assets)).subtract(fee).max(BigInteger.ZERO);
        BigDecimal desired = candidate.subtract(reserve).max(BigDecimal.ZERO).min(fromLovelace(limit));
        return toLovelace(desired).compareTo(minimum(protocol, address, Map.of())) < 0 ? BigDecimal.ZERO : desired;
    }
    public static BigInteger toLovelace(BigDecimal value) { return value.movePointRight(6).toBigIntegerExact(); }
    public static BigDecimal fromLovelace(BigInteger value) { return new BigDecimal(value == null ? BigInteger.ZERO : value).movePointLeft(6).stripTrailingZeros(); }
    private void requireIdentity(CardanoSigningPayload payload) {
        require(HEX.formatHex(rpc.genesis().getGenesis().toByteArray()).equals(payload.unsigned().genesisHash()), "Cardano node differs from the original signing network");
    }
    private static BigInteger feeBudget(ProtocolParams params) {
        return BigInteger.valueOf(params.getMinFeeA()).multiply(BigInteger.valueOf(params.getMaxTxSize())).add(BigInteger.valueOf(params.getMinFeeB()));
    }
    private static TreeMap<String, BigInteger> balances(Query.SearchUtxosResponse snapshot) {
        var result = new TreeMap<String, BigInteger>(); result.put("lovelace", BigInteger.ZERO);
        for (var item : snapshot.getItemsList()) {
            var output = CardanoPublicTransactionBuilder.output(item); result.merge("lovelace", output.getValue().getCoin(), BigInteger::add);
            for (var group : output.getValue().getMultiAssets()) for (var asset : group.getAssets())
                result.merge(MpcCardanoTransactionCodec.assetUnit(group.getPolicyId() + "." + asset.getNameAsHex()), asset.getValue(), BigInteger::add);
        }
        return result;
    }
    private static BigInteger minimum(ProtocolParams params, String address, Map<String, BigInteger> amounts) {
        var groups = new TreeMap<String, List<Asset>>();
        for (var entry : amounts.entrySet()) if (!entry.getKey().equals("lovelace") && entry.getValue().signum() > 0) {
            String unit = entry.getKey(); groups.computeIfAbsent(unit.substring(0, 56), ignored -> new ArrayList<>())
                    .add(new Asset("0x" + unit.substring(56), entry.getValue()));
        }
        var multi = groups.entrySet().stream().map(entry -> new MultiAsset(entry.getKey(), entry.getValue())).toList();
        return new MinAdaCalculator(params).calculateMinAda(new TransactionOutput(address, Value.builder().coin(BigInteger.valueOf(1_000_000)).multiAssets(multi).build()));
    }
    private static Operation funding(Operation op) {
        try { return new Operation(op.tenantId(), "cardano-gas:" + HEX.formatHex(MessageDigest.getInstance("SHA-256")
                .digest((op.kind() + ":" + op.reference()).getBytes(StandardCharsets.UTF_8))), Kind.FUNDING); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
