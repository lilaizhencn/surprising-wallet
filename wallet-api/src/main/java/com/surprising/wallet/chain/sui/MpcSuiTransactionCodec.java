package com.surprising.wallet.chain.sui;

import com.surprising.wallet.sdk.signing.ThresholdSignatureVerifier;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bitcoinj.base.Base58;
import org.bouncycastle.jcajce.provider.digest.Blake2b;

/** Public PTB/object references and external Ed25519 witness. No local wallet key is constructed. */
public final class MpcSuiTransactionCodec {
    private static final HexFormat HEX = HexFormat.of();
    private static final String NATIVE = canonicalCoinType(SuiRpcClient.SUI_COIN_TYPE);
    private MpcSuiTransactionCodec() { }

    public record Unsigned(String network, String chainIdentifier, String sender, String recipient, long amountAtomic,
                           String coinType, List<SuiRpcClient.SuiCoin> inputs, List<SuiRpcClient.SuiCoin> gasPayment,
                           long gasPrice, long gasBudget, String publicKeyHex) {
        public Unsigned {
            require(Set.of("mainnet", "testnet", "devnet", "regtest", "localnet").contains(network), "unsupported Sui network");
            require(chainIdentifier != null && Base58.decode(chainIdentifier).length == 32, "Sui genesis checkpoint identity is required");
            sender = canonicalAddress(sender); recipient = canonicalAddress(recipient); coinType = canonicalCoinType(coinType);
            require(!sender.equals(recipient) && amountAtomic > 0 && gasPrice > 0 && gasBudget > 0, "invalid Sui payment or gas bounds");
            require(publicKeyHex != null && publicKeyHex.matches("[0-9a-f]{64}") && sender.equals(address(HEX.parseHex(publicKeyHex))), "Sui sender differs from joint public key");
            inputs = List.copyOf(inputs); gasPayment = List.copyOf(gasPayment);
            require(!gasPayment.isEmpty() && inputs.size() + gasPayment.size() <= 256, "Sui payment requires bounded owned object references");
            var seen = new HashSet<String>();
            for (var coin : java.util.stream.Stream.concat(inputs.stream(), gasPayment.stream()).toList()) {
                require(coin != null && seen.add(canonicalAddress(coin.objectId())) && Long.parseUnsignedLong(coin.version()) != 0
                        && Base58.decode(coin.digest()).length == 32 && coin.balance() != null
                        && coin.balance().signum() > 0 && coin.balance().toBigIntegerExact().bitLength() <= 64, "Sui coin reference, balance or ownership set differs");
            }
            if (NATIVE.equals(coinType)) {
                require(inputs.isEmpty() && total(gasPayment).compareTo(BigDecimal.valueOf(amountAtomic).add(BigDecimal.valueOf(gasBudget))) >= 0,
                        "Sui native gas objects cannot cover exact principal and budget");
            } else {
                require(!inputs.isEmpty() && total(inputs).compareTo(BigDecimal.valueOf(amountAtomic)) >= 0
                        && total(gasPayment).compareTo(BigDecimal.valueOf(gasBudget)) >= 0, "Sui owned objects cannot cover exact token payment and gas");
            }
        }
    }
    public record Signed(String txBytesBase64, String signatureBase64, String digest) { }

    public static String address(byte[] publicKey) {
        require(publicKey != null && publicKey.length == 32, "Sui joint public key must be 32 bytes");
        byte[] tagged = new byte[33]; System.arraycopy(publicKey, 0, tagged, 1, 32);
        return "0x" + HEX.formatHex(hash(tagged));
    }
    public static byte[] transactionBytes(Unsigned payment) {
        var builder = new SuiPtbTransactionBuilder();
        String value = NATIVE.equals(payment.coinType())
                ? builder.buildSuiTransfer(payment.sender(), payment.gasPayment(), payment.recipient(), payment.amountAtomic(), payment.gasPrice(), payment.gasBudget())
                : builder.buildCoinTransfer(payment.sender(), payment.inputs(), payment.gasPayment(), payment.recipient(), payment.amountAtomic(), payment.gasPrice(), payment.gasBudget(), payment.coinType());
        byte[] bytes = Base64.getDecoder().decode(value);
        require(bytes.length <= 128 * 1024, "Sui PTB exceeds bounded transaction size"); return bytes;
    }
    public static byte[] signingBytes(Unsigned payment) {
        byte[] bcs = transactionBytes(payment), intent = new byte[bcs.length + 3];
        System.arraycopy(bcs, 0, intent, 3, bcs.length); return hash(intent);
    }
    public static String digest(Unsigned payment) {
        return digest(transactionBytes(payment));
    }
    public static String digest(byte[] transactionBytes) {
        require(transactionBytes != null && transactionBytes.length > 0 && transactionBytes.length <= 128 * 1024, "Sui transaction BCS is absent or oversized");
        var bytes = new ByteArrayOutputStream(); bytes.writeBytes("TransactionData::".getBytes(StandardCharsets.UTF_8));
        bytes.writeBytes(transactionBytes); return Base58.encode(hash(bytes.toByteArray()));
    }
    public static Signed assemble(Unsigned payment, byte[] signature) {
        require(signature != null && ThresholdSignatureVerifier.ed25519(HEX.parseHex(payment.publicKeyHex()), signingBytes(payment), signature), "Sui MPC witness differs from fixed transaction");
        var bytes = new ByteArrayOutputStream(); bytes.write(0); bytes.writeBytes(signature); bytes.writeBytes(HEX.parseHex(payment.publicKeyHex()));
        return new Signed(Base64.getEncoder().encodeToString(transactionBytes(payment)), Base64.getEncoder().encodeToString(bytes.toByteArray()), digest(payment));
    }
    public static void verifySelected(Unsigned payment, Signed selected) {
        require(selected != null && selected.signatureBase64() != null, "Sui selected signature is absent");
        byte[] bytes = Base64.getDecoder().decode(selected.signatureBase64());
        require(bytes.length == 97 && bytes[0] == 0 && Arrays.equals(Arrays.copyOfRange(bytes, 65, 97), HEX.parseHex(payment.publicKeyHex())), "Sui witness scheme or joint public key differs");
        require(assemble(payment, Arrays.copyOfRange(bytes, 1, 65)).equals(selected), "Sui selected raw, digest or witness differs from fixed payment");
    }
    public static String canonicalAddress(String value) { return SuiHex.normalizeAddress(value); }
    public static String canonicalCoinType(String value) {
        require(value != null, "Sui Coin type is required"); String[] parts = value.split("::", -1);
        require(parts.length == 3 && parts[1].matches("[A-Za-z_][A-Za-z0-9_]{0,127}") && parts[2].matches("[A-Za-z_][A-Za-z0-9_]{0,127}"), "unsupported configured Sui Coin type");
        return canonicalAddress(parts[0]) + "::" + parts[1] + "::" + parts[2];
    }
    private static BigDecimal total(List<SuiRpcClient.SuiCoin> coins) { return coins.stream().map(SuiRpcClient.SuiCoin::balance).reduce(BigDecimal.ZERO, BigDecimal::add); }
    private static byte[] hash(byte[] bytes) { return new Blake2b.Blake2b256().digest(bytes); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
