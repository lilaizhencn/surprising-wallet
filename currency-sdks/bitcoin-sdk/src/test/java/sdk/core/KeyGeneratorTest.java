package sdk.core;

import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.TestNet3Params;

import java.security.SecureRandom;

public class KeyGeneratorTest {

    public static void main(String[] args) {

        //NetworkParameters params = MainNetParams.get(); // TestNet3Params.get();
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
//        priKey-1:tprv8ZgxMBicQKsPfBRzVSv3hF81ytoWmdk1de6JEbe5Qg2qpEhLAiqJ6CG76rytrWK6t5ZqeRTzR3eLEAbUM6euY3wB5kV43jSZxfEJ8NcQs3m
//        pubKey-1:tpubD6NzVbkrYhZ4YeTnP6ae6en8YvKSvxvvCwh5X7gNpwqEeix6o7etGgsyGywcB9gS1bGTmC4WfLKAdK6vxDEzedd7PMRLcYk5yZLj5JkLAVB
//        priKey-2:tprv8ZgxMBicQKsPdSLEi7y5GPRhZ7YsGD5Vuu4YMXR22nc76dadpcK95WdgRSB7V3LAhDEWDBiJg1F5TYXFWHMGZLx99f3zSwWkMWD6MPe627j
//        pubKey-2:tpubD6NzVbkrYhZ4WuN2bmdffo5p894oRYGQVCfKe3TKT4QVw7qQT18jG1FYbYyB3ePESejLdfaEFMRpsYGVjb4Bh6HiiWaSU8iJRVE46EirNBT
//        priKey-3:tprv8ZgxMBicQKsPdrd7YeHKdQPmDHSaaFRMJbS4wifacZZBWazmrdcAQrxS4QBuxy3fyCfVV7bWNKwr4XX5pgTSLJAsdB7kLsu7NgjSFwPLCDB
//        pubKey-3:tpubD6NzVbkrYhZ4XKeuSHwv2p3snJxWjacFsu2rEEht2qMaM5FYV2RkbMaJEYNZGK7B3i8D46RTs83DJNPh2Jd5MzXivXCiHLbqAFKv8MKxrC4
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
