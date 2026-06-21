package sdk.core;

import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.TestNet3Params;

import java.security.SecureRandom;

public class KeyGeneratorTest {

    public static void main(String[] args) {

        NetworkParameters params = TestNet3Params.get();
        Bip32Node node1 = Bip32Node.getMasterKey(getSeed(256));
        Bip32Node node2 = Bip32Node.getMasterKey(getSeed(256));
        Bip32Node node3 = Bip32Node.getMasterKey(getSeed(256));
        String priKey = node1.privSerialize(0, false);
        String pubKey = node1.pubSerialize(0, false);
        System.out.println("priKey-1:" + priKey);
        System.out.println("pubKey-1:" + pubKey);
        priKey = node2.privSerialize(0, false);
        pubKey = node2.pubSerialize(0, false);
        System.out.println("priKey-2:" + priKey);
        System.out.println("pubKey-2:" + pubKey);

        priKey = node3.privSerialize(0, false);
        pubKey = node3.pubSerialize(0, false);
        System.out.println("priKey-3:" + priKey);
        System.out.println("pubKey-3:" + pubKey);
    }


    public static byte[] getSeed(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be greater than 0");
        }
        byte[] seed = new byte[size];
        try {
            SecureRandom.getInstance("SHA1PRNG", "SUN").nextBytes(seed);
        } catch (Exception e) {
            try {
                SecureRandom.getInstance("SHA1PRNG").nextBytes(seed);
            } catch (Exception e1) {
                new SecureRandom().nextBytes(seed);
            }
        }
        return seed;
    }

}
