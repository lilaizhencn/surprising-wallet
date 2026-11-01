package com.surprising.wallet.common.signing;

import com.surprising.wallet.sdk.signing.MoneroPublicAddressCodec;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Public policy checks. Native transaction validity requires matching authenticated quorum results. */
public final class MoneroMpcResultVerifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private MoneroMpcResultVerifier() { }

    /** Recheck an already authenticated two-holder observation at its business settlement boundary. */
    public static void observation(MpcClient.Request request,MpcClient.Result result) {
        require(request.algorithm()==MpcClient.Algorithm.MONERO_NATIVE_2_OF_3 && request.operation()==MpcClient.Operation.MONERO_OBSERVE
                && result.state()==MpcClient.State.COMPLETED && request.sessionId().equals(result.sessionId())
                && request.tenantId().equals(result.tenantId()) && request.keyId().equals(result.keyId()) && request.epoch()==result.epoch()
                && intent(request).equals(result.intentHash()),"native Monero observation request identity differs");
        verify(request,result.publicKeyHex(),result.publicAddress(),result.signatureHex(),result.monero());
    }

    static void verify(MpcClient.Request request, String publicKey, String address, String signature, JsonNode facts) {
        var policy = policy(request);
        hash(policy.path("genesisHash"));
        if (request.operation() == MpcClient.Operation.CREATE_KEY) {
            String kind = policy.path("kind").asText();
            require(kind.equals("ROOT") || kind.equals("ADDRESS"), "invalid native Monero creation policy");
            publicAddress(publicKey, address, policy.path("network").asText(), kind.equals("ADDRESS"));
            require(signature == null && facts == null, "native Monero creation returned unexpected transaction material");
            return;
        }
        publicAddress(publicKey, address, policy.path("network").asText(), true);
        require(address.equals(policy.path("sender").asText()), "native Monero result sender differs");
        switch (request.operation()) {
            case SIGN -> {
                require(facts == null, "native Monero signing returned unrelated observations");
                artifact(request, publicKey, signature);
            }
            case MONERO_RELAY -> {
                require(signature == null && policy.path("kind").asText().equals("RELAY") && facts != null
                    && facts.path("kind").asText().equals("RELAY"), "invalid native Monero relay result");
                require(facts.path("transactionHash").equals(policy.path("transactionHash"))
                    && facts.path("signSessionId").equals(policy.path("signSessionId"))
                    && facts.path("signIntentHash").equals(policy.path("signIntentHash")), "native Monero relay lost its original signing binding");
                hash(facts.path("transactionHash")); hash(facts.path("signIntentHash"));
            }
            case MONERO_OBSERVE -> {
                require(signature == null && policy.path("kind").asText().equals("SNAPSHOT") && facts != null
                    && facts.path("kind").asText().equals("SNAPSHOT"), "invalid native Monero observation result");
                require(facts.path("address").asText().equals(address)
                    && facts.path("network").equals(policy.path("network")) && facts.path("genesisHash").equals(policy.path("genesisHash"))
                    && facts.path("anchorHash").equals(policy.path("blockHash"))
                    && number(facts.path("anchorHeight"), false).equals(number(policy.path("height"), false)), "native Monero snapshot anchor differs");
                hash(facts.path("anchorHash")); hash(facts.path("viewHash"));
                var view = number(facts.path("viewHeight"), false);
                require(view.compareTo(number(facts.path("anchorHeight"), false)) >= 0
                    && number(facts.path("accountIndex"), false).signum() > 0
                    && number(facts.path("unlockedBalanceAtomic"), true).compareTo(number(facts.path("balanceAtomic"), true)) <= 0,
                    "native Monero snapshot balance or view differs");
                var outputs = facts.path("outputs");
                require(outputs.isArray() && outputs.size() <= 10000, "native Monero snapshot output bound differs");
                var unique = new HashSet<String>();
                var positions = new HashSet<String>();
                for (var output : outputs) {
                    hash(output.path("transactionHash")); hash(output.path("outputKey")); hash(output.path("blockHash"));
                    require(unique.add(output.path("outputKey").asText()) && number(output.path("amountAtomic"), true).signum() > 0
                        && number(output.path("blockHeight"), false).compareTo(number(policy.path("fromHeight"), false)) >= 0
                        && number(output.path("blockHeight"), false).compareTo(number(policy.path("height"), false)) <= 0,
                        "native Monero output identity, amount or range differs");
                    number(output.path("globalIndex"), false);
                    var index = number(output.path("outputIndex"), false);
                    require(index.compareTo(BigInteger.valueOf(10000)) < 0
                        && positions.add(output.path("transactionHash").asText() + ":" + index)
                        && output.path("coinbase").isBoolean() && output.path("spent").isBoolean()
                        && output.path("frozen").isBoolean() && output.path("unlocked").isBoolean(),
                        "native Monero original output position or lock state differs");
                    number(output.path("unlockTime"), false);
                    var source = output.path("sourceAddress");
                    require(output.has("sourceAddress") && (source.isNull() || source.isTextual()
                        && MoneroPublicAddressCodec.isValid(source.asText(), policy.path("network").asText()))
                        && output.path("change").isBoolean()
                        && output.path("change").asBoolean() == (source.isTextual() && source.asText().equals(address)),
                        "native Monero original source or change identity differs");
                    require(view.subtract(number(output.path("blockHeight"), false)).add(BigInteger.ONE)
                        .equals(number(output.path("confirmations"), false)), "native Monero output maturity differs from its canonical view");
                    if (output.path("blockHeight").equals(facts.path("anchorHeight")))
                        require(output.path("blockHash").equals(facts.path("anchorHash")), "native Monero output differs from its anchor block");
                }
                if (policy.has("transactionHash")) {
                    var observed = facts.path("transaction");
                    require(observed.path("transactionHash").equals(policy.path("transactionHash"))
                        && observed.path("known").isBoolean(), "native Monero transaction observation identity differs");
                    if (observed.path("known").asBoolean()) {
                        require(observed.path("inPool").isBoolean() && observed.path("doubleSpendSeen").isBoolean()
                            && observed.path("coinbase").isBoolean(), "native Monero transaction state is absent");
                        number(observed.path("unlockTime"), false);
                        if (!observed.path("inPool").asBoolean()) {
                            hash(observed.path("blockHash"));
                            require(view.subtract(number(observed.path("blockHeight"), false)).add(BigInteger.ONE)
                                .equals(number(observed.path("confirmations"), false)), "native Monero confirmation count differs from its view");
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException("unsupported native Monero result operation");
        }
    }

    /** Validate an opaque artifact again when retrieving it from durable wallet storage. */
    public static void artifact(MpcClient.Request request, String publicKey, String signatureHex) {
        require(request.algorithm() == MpcClient.Algorithm.MONERO_NATIVE_2_OF_3
            && request.operation() == MpcClient.Operation.SIGN, "native Monero artifact requires its signing request");
        var policy = policy(request);
        publicAddress(publicKey, policy.path("sender").asText(), policy.path("network").asText(), true);
        require(policy.path("kind").asText().equals("PAYMENT")
            && MoneroPublicAddressCodec.isValid(policy.path("recipient").asText(), policy.path("network").asText()), "native Monero payment policy differs");
        require(hex(signatureHex, 1024 * 1024), "native Monero artifact encoding differs");
        var artifact = JSON.readTree(HexFormat.of().parseHex(signatureHex));
        require(artifact.path("protocol").asText().equals("MONERO_NATIVE_2_OF_3")
            && artifact.path("tenantId").asText().equals(request.tenantId().toString())
            && artifact.path("keyId").asText().equals(request.keyId().toString())
            && artifact.path("sessionId").asText().equals(request.sessionId().toString())
            && artifact.path("intentHash").asText().equals(intent(request)) && artifact.path("policy").equals(policy)
            && artifact.path("online").equals(JSON.valueToTree(request.online())), "native Monero artifact intent binding differs");
        hash(artifact.path("transactionHash"));
        require(hex(artifact.path("transactionSetHex").asText(), 256 * 1024), "native Monero transaction set encoding differs");
        var description = artifact.path("description");
        var amount = number(policy.path("amountAtomic"), true);
        var fee = number(artifact.path("feeAtomic"), true);
        require(amount.signum() > 0 && fee.equals(number(description.path("fee"), false))
            && fee.compareTo(number(policy.path("maximumFeeAtomic"), true)) <= 0
            && number(description.path("unlock_time"), false).signum() == 0
            && number(description.path("ring_size"), false).equals(BigInteger.valueOf(16)), "native Monero payment fee, ring or unlock time differs");
        var change = number(description.path("change_amount"), false);
        var inputs = number(description.path("amount_in"), false);
        require(change.signum() > 0 && description.path("change_address").equals(policy.path("sender"))
            && inputs.equals(amount.add(change).add(fee)) && number(description.path("amount_out"), false).equals(inputs.subtract(fee)),
            "native Monero original input or change amount differs");
        var recipients = description.path("recipients");
        require(recipients.isArray() && recipients.size() == 1 && recipients.get(0).path("address").equals(policy.path("recipient"))
            && number(recipients.get(0).path("amount"), false).equals(amount), "native Monero exact recipient differs");
        var sources = description.path("sources");
        require(sources.isArray() && !sources.isEmpty() && sources.size() <= 1000, "native Monero original sources are absent");
        var sum = BigInteger.ZERO;
        var unique = new HashSet<String>();
        for (var source : sources) {
            String key = source.path("pubkey").asText();
            require(key.matches("[0-9a-f]{128}") && unique.add(key.substring(0, 64)), "native Monero source key is invalid or repeated");
            number(source.path("global_index"), false);
            sum = sum.add(number(source.path("amount"), false));
        }
        require(sum.equals(inputs), "native Monero original source amount differs");
    }

    /** Each map entry must come from the MpcClient pinned to that party's SPKI. */
    public static boolean complete(MpcClient.Request request, Map<String, MpcClient.Result> results) {
        require(request.algorithm() == MpcClient.Algorithm.MONERO_NATIVE_2_OF_3, "native Monero quorum requires its protocol");
        if (!results.keySet().containsAll(request.online()) || request.online().stream()
            .anyMatch(party -> results.get(party).state() != MpcClient.State.COMPLETED)) return false;
        var first = results.get(request.online().getFirst());
        require(request.online().stream().allMatch(party -> first.equals(results.get(party))), "native Monero authenticated quorum results disagree");
        return true;
    }

    private static JsonNode policy(MpcClient.Request request) { return JSON.readTree(HexFormat.of().parseHex(request.messageHex())); }
    private static void publicAddress(String publicKey, String address, String network, boolean subaddress) {
        var decoded = MoneroPublicAddressCodec.decode(address);
        require(decoded.network() == MoneroPublicAddressCodec.Network.parse(network) && decoded.subaddress() == subaddress
            && decoded.paymentId().length == 0 && publicKey.equals(HexFormat.of().formatHex(decoded.publicKeys())), "native Monero public key/address binding differs");
    }
    private static String intent(MpcClient.Request request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(MpcClient.canonical(request)))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void hash(JsonNode value) { require(value.isTextual() && value.asText().matches("[0-9a-f]{64}"), "native Monero hash encoding differs"); }
    private static boolean hex(String value, int maximum) { return value != null && !value.isEmpty() && value.length() <= maximum
        && value.length() % 2 == 0 && value.matches("[0-9a-f]+"); }
    private static BigInteger number(JsonNode value, boolean text) {
        require(text ? value.isTextual() : value.isIntegralNumber(), "native Monero amount type differs");
        require(value.asText().matches("0|[1-9][0-9]{0,19}"), "native Monero amount encoding differs");
        var amount = new BigInteger(value.asText());
        require(amount.bitLength() <= 64, "native Monero amount exceeds uint64"); return amount;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
