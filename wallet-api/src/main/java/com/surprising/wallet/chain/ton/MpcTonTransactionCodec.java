package com.surprising.wallet.chain.ton;

import com.surprising.wallet.sdk.signing.ThresholdSignatureVerifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellBuilder;
import org.ton.ton4j.cell.CellSlice;
import org.ton.ton4j.smartcontract.token.ft.JettonWalletV2;
import org.ton.ton4j.smartcontract.utils.MsgUtils;
import org.ton.ton4j.smartcontract.wallet.v4.WalletV4R2;
import org.ton.ton4j.tlb.Message;

/** Public-only V4R2 native/TEP-74 payment encoding with a fixed network, seqno and lifetime. */
public final class MpcTonTransactionCodec {
    public static final BigInteger JETTON_ATTACHED_NANO = BigInteger.valueOf(70_000_000);
    public static final BigInteger JETTON_FORWARD_NANO = BigInteger.valueOf(10_000_000);
    private static final HexFormat HEX = HexFormat.of();
    private MpcTonTransactionCodec() { }

    /** The wallet ID is signed and changes StateInit; a friendly address's test flag alone does neither. */
    public static long walletId(String network) {
        return switch (network) {
            case "mainnet" -> 698_983_191L;
            case "testnet" -> 698_983_191L ^ 0x8000_0000L;
            case "regtest" -> 698_983_191L ^ 0x4000_0000L;
            default -> throw new IllegalArgumentException("unsupported TON signing network");
        };
    }

    public static WalletV4R2 wallet(String network, byte[] publicKey) {
        require(publicKey != null && publicKey.length == 32, "TON joint public key must be 32 bytes");
        return WalletV4R2.builder().publicKey(publicKey.clone()).walletId(walletId(network)).wc(0).build();
    }

    public static String address(String network, byte[] publicKey) {
        return wallet(network, publicKey).getAddress().toString(true, true, false, !"mainnet".equals(network));
    }

    public record Unsigned(String network, String sender, String recipient, BigInteger amountAtomic,
                           String jettonMaster, String sourceJettonWallet, String recipientJettonWallet,
                           long sequence, long validUntil, long queryId, String memo, String publicKeyHex) {
        public Unsigned {
            walletId(network);
            sender = canonicalAddress(sender);
            recipient = canonicalAddress(recipient);
            require(!sender.equals(recipient), "TON source and recipient must differ");
            require(amountAtomic != null && amountAtomic.signum() > 0 && amountAtomic.bitLength() <= 120,
                    "invalid TON payment amount");
            require(sequence >= 0 && sequence <= 0xffff_ffffL && validUntil > 0 && validUntil <= 0xffff_ffffL
                    && queryId >= 0, "invalid TON sequence, expiry or query ID");
            require(memo != null && !memo.isBlank() && memo.getBytes(StandardCharsets.UTF_8).length <= 120,
                    "TON business memo is required and must fit the bounded payment body");
            require(publicKeyHex != null && publicKeyHex.matches("[0-9a-f]{64}"), "invalid TON joint public key");
            require(sender.equals(wallet(network, HEX.parseHex(publicKeyHex)).getAddress().toRaw()),
                    "TON sender differs from joint public key and network wallet ID");
            if (jettonMaster == null) {
                require(sourceJettonWallet == null && recipientJettonWallet == null && queryId == 0,
                        "native TON payment must not contain Jetton fields");
            } else {
                jettonMaster = canonicalAddress(jettonMaster);
                sourceJettonWallet = canonicalAddress(sourceJettonWallet);
                recipientJettonWallet = canonicalAddress(recipientJettonWallet);
                require(queryId > 0 && !sourceJettonWallet.equals(recipientJettonWallet), "invalid Jetton payment identity");
                require(Address.of(jettonMaster).wc==0 && Address.of(sourceJettonWallet).wc==0
                        && Address.of(recipientJettonWallet).wc==0 && Address.of(recipient).wc==0,
                        "configured TEP-74 payment wallets and recipient must be in the basechain");
            }
        }
    }

    public record Signed(String bocBase64, String messageHashHex) { }

    public static Cell payload(Unsigned payment) {
        Cell payload = MsgUtils.createTextMessageBody(payment.memo());
        if (payment.jettonMaster() != null) {
            payload = JettonWalletV2.createTransferBody(payment.queryId(), payment.amountAtomic(),
                    Address.of(payment.recipient()), Address.of(payment.sender()), null,
                    JETTON_FORWARD_NANO, payload);
        }
        return payload;
    }

    public static Cell body(Unsigned payment) {
        boolean jetton = payment.jettonMaster() != null;
        var outgoing = MsgUtils.createInternalMessageRelaxed(
                Address.of(jetton ? payment.sourceJettonWallet() : payment.recipient()),
                jetton ? JETTON_ATTACHED_NANO : payment.amountAtomic(), null, null, payload(payment), jetton);
        // mode=3 pays fees separately and skips eligible send errors, preserving the consumed seqno.
        return CellBuilder.beginCell().storeUint(walletId(payment.network()),32)
                .storeUint(payment.validUntil(),32).storeUint(payment.sequence(),32)
                .storeUint(0,8).storeUint(3,8).storeRef(outgoing.toCell()).endCell();
    }

    public static byte[] signingBytes(Unsigned payment) { return body(payment).hash(); }

    public static Signed assemble(Unsigned payment, byte[] suppliedSignature) {
        require(suppliedSignature != null, "TON MPC signature is absent");
        byte[] signature = suppliedSignature.clone();
        byte[] publicKey = HEX.parseHex(payment.publicKeyHex());
        require(ThresholdSignatureVerifier.ed25519(publicKey, signingBytes(payment), signature),
                "TON MPC signature differs from fixed payment");
        var contract = wallet(payment.network(), publicKey);
        var message = MsgUtils.createExternalMessageWithSignedBody(signature, contract.getAddress(),
                payment.sequence() == 0 ? contract.getStateInit() : null, body(payment)).toCell();
        return new Signed(Base64.getEncoder().encodeToString(message.toBoc(false)), HEX.formatHex(message.hash()));
    }

    /** Reconstruct the exact retained message, including its sole signature and optional StateInit. */
    public static void verifySelected(Unsigned payment, Signed selected) {
        require(selected != null && selected.bocBase64() != null && selected.bocBase64().length() <= 8192,
                "invalid selected TON message");
        byte[] bytes = Base64.getDecoder().decode(selected.bocBase64());
        var message = Message.deserialize(CellSlice.beginParse(Cell.fromBoc(bytes)));
        byte[] signature = CellSlice.beginParse(message.getBody()).loadBytes(512);
        var expected = assemble(payment, signature);
        require(expected.messageHashHex().equals(selected.messageHashHex())
                        && Arrays.equals(Base64.getDecoder().decode(expected.bocBase64()), bytes),
                "selected TON message differs from fixed payment, witness or StateInit");
    }

    public static String canonicalAddress(String value) {
        require(value != null && !value.isBlank(), "TON address is required");
        Address parsed = Address.of(value);
        require(parsed.wc == 0 || parsed.wc == -1, "unsupported TON address workchain");
        return parsed.toRaw();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
