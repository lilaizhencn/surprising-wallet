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
//        priKey-1:xprv9s21ZrQH143K431pDGxJuAKXo4CDBiVwvhStkVoRhXLorqDd7VtUaaBGBWiCkZNraMKvHW3DREHNeQi2x57ZuBCYQzFmNGztfeVZGSntv5W
//        pubKey-1:xpub661MyMwAqRbcGX6HKJVKGJGGM62hbBDoHvNVYtD3FrsnjdYmf3Cj8NVk2nRun88n7bPmn4KVrmJrvdyBEZ7yvH3SYHPsjiFV2FCRh9ohXMu
//        priKey-2:xprv9s21ZrQH143K3Nn2BdhZf5d4fykLjxZK8RdsuhMUTiAPP5df77KjQace6heAUMdK31X4NisfaXqe1899UF5prEn6WH83fhUipPeLXj2SrZC
//        pubKey-2:xpub661MyMwAqRbcFrrVHfEa2DZoE1aq9RHAVeZUi5m623hNFsxoeedyxNw7x1vzYCfig2xtu3t9YB43gbYnsxmQbLDsPRKWuiBXsX2iU2qZ1xJ
//        priKey-3:xprv9s21ZrQH143K2HptkhuvEzuccCkFcsQPsowk5GHkYt6wqJExTbkZTSaLsSLjHqm9CsiPjKa3c1MiWyS2yHiWVGhmb8xJAA5PwSog8H1h13c
//        pubKey-3:xpub661MyMwAqRbcEmuMrjSvc8rMAEak2L8FF2sLsehN7Ddvi6a7194p1EtpigvagKELZYrbCJPma1aGvYUu5GaPhej18iqynHiPVWarW8Jbg13

//        priKey-1:tprv8ZgxMBicQKsPdbs7SqHoQoG3z9zbLhaqKkC6puxUCiT6iR656FezRhnAhdykJjLanDqqZRGimT8yvHM7sZmWAXeEvk8UPxVMSZ1sh6uJFJc
//        pubKey-1:tpubD6NzVbkrYhZ4X4tuLUxPpCvAZBWXW2mju3nt7RzmczFVYuLqieUacCQ2snjhu8zE9EagGrkG5gxthYUFhpgYx4bEVM9dYraywwZHycJNg5d
//        priKey-2:tprv8ZgxMBicQKsPdMCWhEWHyiZCAu45dhRgupjpWBJ4vaYojzWURTemEm51Kf8tGQVcuerPJhCh98aCL9E81C2je6k3aeT7AJ5xELrVMprXf8U
//        pubKey-2:tpubD6NzVbkrYhZ4WpEJatAtP8DJjva1o2cbV8LbnhLNLrMCaUmF3rUMRFgsVneh53JipwKMwUp3QGGzqff7avf5M9QLR7RZaEy3ha5ihtrkDRQ
//        priKey-3:tprv8ZgxMBicQKsPdkGb11HUsMMBRhYfSKknEWW6cSvGVo6TiZHUzwvRdet4pF1THcu2SSQyKcdskg3dWkHJ6AViWwXTUPXwkUq6EWqHw4nNDPy
//        pubKey-3:tpubD6NzVbkrYhZ4XDJNtex5Gm1Hzj4bbewgop6stxxZv4trZ3YFdLk1p9VvzQtYTk8Lf4BMo5pWA5TwJGgFbbEFaBr5Ft951V2wYQcMLWQNji4
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
