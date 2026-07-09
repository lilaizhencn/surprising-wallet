package sdk.core;

import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.TestNet3Params;

import java.security.SecureRandom;
import java.util.Arrays;

public class KeyGeneratorTest {

    public static void main(String[] args) {
        int coinType = 0;
        boolean isMainnet = false;// false = 生成测试网的密钥对，true=生成主网的密钥对
        //由于 btc 系的链都使用多签地址(2 of 3) 所以需要用 3 个 seed。生成 3 对根私钥
        //第一个 seed
        byte[] seed1 = getSeed(256);

        String seed1Str = ByteUtils.formatHex(seed1);
        byte[] seed1Parse = ByteUtils.parseHex(seed1Str);
        System.out.println(Arrays.toString(seed1));
        System.out.println(Arrays.toString(seed1Parse));
        //第二个 seed
        byte[] seed2 = getSeed(256);
        //第三个 seed
        byte[] seed3 = getSeed(256);
        //第一个根私钥
        Bip32Node node1 = Bip32Node.getMasterKey(seed1);
        //第二个根私钥
        Bip32Node node2 = Bip32Node.getMasterKey(seed2);
        //第三个根私钥
        Bip32Node node3 = Bip32Node.getMasterKey(seed3);
        // pub priv 1
        String priKey = node1.privSerialize(coinType, isMainnet);
        String pubKey = node1.pubSerialize(coinType, isMainnet);
        System.out.println("priKey-1:" + priKey);
        System.out.println("pubKey-1:" + pubKey);
        // pub priv 2
        priKey = node2.privSerialize(coinType, isMainnet);
        pubKey = node2.pubSerialize(coinType, isMainnet);
        System.out.println("priKey-2:" + priKey);
        System.out.println("pubKey-2:" + pubKey);
        // pub priv 3
        priKey = node3.privSerialize(coinType, isMainnet);
        pubKey = node3.pubSerialize(coinType, isMainnet);
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
