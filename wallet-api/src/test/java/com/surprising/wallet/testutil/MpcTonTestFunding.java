package com.surprising.wallet.testutil;

import com.surprising.wallet.chain.ton.TonCenterClient;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.ton.ton4j.smartcontract.highload.HighloadWallet;
import org.ton.ton4j.smartcontract.types.Destination;
import org.ton.ton4j.smartcontract.types.HighloadConfig;
import org.ton.ton4j.smartcontract.utils.MsgUtils;
import org.ton.ton4j.utils.Utils;

/** Isolated MyLocalTon genesis fixture only. No faucet process or auxiliary database is used. */
public final class MpcTonTestFunding {
    private MpcTonTestFunding() { }
    public static String fund(TonCenterClient rpc, Path fixtureKey, String recipient, long amountNano) throws Exception {
        if (!fixtureKey.isAbsolute() || !Files.isRegularFile(fixtureKey) || amountNano <= 0) {
            throw new IllegalArgumentException("Protected local genesis fixture key file and positive amount required");
        }
        byte[] seed=Files.readAllBytes(fixtureKey);
        if(seed.length!=32)throw new IllegalArgumentException("Local genesis funding fixture must have a 32-byte seed");
        var pair=Utils.generateSignatureKeyPairFromSeed(seed);
        try {
            var wallet=HighloadWallet.builder().keyPair(pair).wc(-1).walletId(42L).build();
            if(!"active".equals(rpc.addressInformation(wallet.getAddress().toRaw()).path("state").asText())) {
                throw new IllegalStateException("Local genesis funding wallet is not active");
            }
            var nonce=BigInteger.valueOf(Instant.now().plusSeconds(120).getEpochSecond()).shiftLeft(32)
                    .or(BigInteger.valueOf(Integer.toUnsignedLong(new SecureRandom().nextInt())));
            var body=wallet.createTransferBody(HighloadConfig.builder().walletId(42L).queryId(nonce)
                    .destinations(List.of(Destination.builder().bounce(false).address(recipient)
                            .amount(BigInteger.valueOf(amountNano)).comment("mpc-isolated-fixture-funding").build())).build());
            var message=MsgUtils.createExternalMessageWithSignedBody(
                    Utils.signData(pair.getPublicKey(),pair.getSecretKey(),body.hash()),wallet.getAddress(),null,body).toCell();
            long before=rpc.balance(recipient);
            String observed=rpc.sendBoc(message.toBoc(false));
            if(!TonCenterClient.sameHash(observed,HexFormat.of().formatHex(message.hash()))) {
                throw new IllegalStateException("Local fixture returned a different funding message hash");
            }
            long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
            while(rpc.balance(recipient)<before+amountNano && System.nanoTime()<deadline)Thread.sleep(100);
            if(rpc.balance(recipient)<before+amountNano)throw new AssertionError("Local genesis funding did not arrive");
            return wallet.getAddress().toRaw();
        } finally {Arrays.fill(seed,(byte)0);Arrays.fill(pair.getSecretKey(),(byte)0);}
    }
}
