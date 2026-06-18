package com.surprising.wallet.sdk.bitcoinj.core;

import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Utils;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptPattern;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 多签地址生成类。
 *
 * @author lilaizhencn
 */
public class MultiSignAddressGenerator {

    // 生成多签地址需要的公钥列表。

    private List<ECKey> ecKeyList = new ArrayList<>();

    // 多签地址的脚本。

    private Script redeemScript;

    private int minSignNum;

    /**
     * 添加一个公钥。
     *
     * @param pubKey
     * @throws Exception
     */
    public void addECKey(ECKey pubKey) {
        if (pubKey == null || ecKeyList.size() >= 16) {
            throw new IllegalArgumentException("最多只能添加16个非空公钥！");
        }
        ecKeyList.add(pubKey);
        redeemScript = null;
        minSignNum = 0;
    }

    /**
     * 替换队列中指定位置的公钥。只能替换已经添加的公钥。
     *
     * @param index
     * @param pubKey
     * @return
     */
    public boolean setECKey(int index, ECKey pubKey) {
        if (index < 0 || index > ecKeyList.size() - 1 || pubKey == null) {
            return false;
        }
        ecKeyList.set(index, pubKey);
        redeemScript = null;
        minSignNum = 0;
        return true;
    }

    /**
     * 根据传入的公钥，构建指定类型网络的多签地址。
     *
     * @param params
     * @param minSignNum
     * @return
     * @throws IllegalArgumentException
     */
    public String generateAddress(NetworkParameters params, int minSignNum) {
        int size = ecKeyList.size();

        if (size < 2) {
            throw new IllegalArgumentException("添加的公钥数量不足！");
        }
        if (minSignNum < 1) {
            throw new IllegalArgumentException("生成的地址最少需要一个签名！");
        }
        if (minSignNum > size) {
            minSignNum = size;
        }

        redeemScript = ScriptBuilder.createMultiSigOutputScript(minSignNum, ecKeyList);
        Script p2shScript = ScriptBuilder.createP2SHOutputScript(redeemScript);

        LegacyAddress address = LegacyAddress.fromScriptHash(params, ScriptPattern.extractHashFromP2SH(p2shScript));
        this.minSignNum = minSignNum;
        return address.toBase58();
    }

    /**
     * 获取签名脚本。
     *
     * @return
     */
    public Script getRedeemScript() {
        return redeemScript;
    }

    /**
     * 获取16进制编码的签名脚本。
     *
     * @return
     * @throws Exception
     */
    public String getScriptStr() {
        if (redeemScript == null) {
            return null;
        }
        return Utils.HEX.encode(redeemScript.getProgram());
    }

    /**
     * 设置公钥列表。
     *
     * @param ecKeyList： 不能为空，且会将null元素剔除。
     */
    public void setEcKeyList(List<ECKey> ecKeyList) {
        if (ecKeyList == null) {
            return;
        }

        // 列表非空时，剔除null元素。
        if (ecKeyList.size() != 0) {
            Iterator<ECKey> iter = ecKeyList.iterator();
            while (iter.hasNext()) {
                ECKey temp = iter.next();
                if (temp == null) {
                    iter.remove();
                }
            }
        }

        this.ecKeyList = ecKeyList;
        redeemScript = null;
        minSignNum = 0;
    }

    /**
     * 获取生成多签地址的公钥列表。
     *
     * @return
     */
    public List<ECKey> getEcKeyList() {
        return ecKeyList;
    }

    /**
     * 返回生成的多签地址签名所需的最少数量。
     *
     * @return
     */
    public int getMinSignNum() {
        return minSignNum;
    }

    /**
     * 返回生成多签地址的公钥的总数量。
     *
     * @return
     */
    public int getMaxSignNum() {
        return ecKeyList.size();
    }

//        priKey-1:tprv8ZgxMBicQKsPdbs7SqHoQoG3z9zbLhaqKkC6puxUCiT6iR656FezRhnAhdykJjLanDqqZRGimT8yvHM7sZmWAXeEvk8UPxVMSZ1sh6uJFJc
//        pubKey-1:tpubD6NzVbkrYhZ4X4tuLUxPpCvAZBWXW2mju3nt7RzmczFVYuLqieUacCQ2snjhu8zE9EagGrkG5gxthYUFhpgYx4bEVM9dYraywwZHycJNg5d
//        priKey-2:tprv8ZgxMBicQKsPdMCWhEWHyiZCAu45dhRgupjpWBJ4vaYojzWURTemEm51Kf8tGQVcuerPJhCh98aCL9E81C2je6k3aeT7AJ5xELrVMprXf8U
//        pubKey-2:tpubD6NzVbkrYhZ4WpEJatAtP8DJjva1o2cbV8LbnhLNLrMCaUmF3rUMRFgsVneh53JipwKMwUp3QGGzqff7avf5M9QLR7RZaEy3ha5ihtrkDRQ
//        priKey-3:tprv8ZgxMBicQKsPdkGb11HUsMMBRhYfSKknEWW6cSvGVo6TiZHUzwvRdet4pF1THcu2SSQyKcdskg3dWkHJ6AViWwXTUPXwkUq6EWqHw4nNDPy
//        pubKey-3:tpubD6NzVbkrYhZ4XDJNtex5Gm1Hzj4bbewgop6stxxZv4trZ3YFdLk1p9VvzQtYTk8Lf4BMo5pWA5TwJGgFbbEFaBr5Ft951V2wYQcMLWQNji4


    public static void main(String[] args) {
        Bip32Node NODE1 = Bip32Node.decode("tprv8ZgxMBicQKsPdbs7SqHoQoG3z9zbLhaqKkC6puxUCiT6iR656FezRhnAhdykJjLanDqqZRGimT8yvHM7sZmWAXeEvk8UPxVMSZ1sh6uJFJc");
        Bip32Node NODE2 = Bip32Node.decode("tprv8ZgxMBicQKsPdMCWhEWHyiZCAu45dhRgupjpWBJ4vaYojzWURTemEm51Kf8tGQVcuerPJhCh98aCL9E81C2je6k3aeT7AJ5xELrVMprXf8U");
        Bip32Node NODE3 = Bip32Node.decode("tprv8ZgxMBicQKsPdkGb11HUsMMBRhYfSKknEWW6cSvGVo6TiZHUzwvRdet4pF1THcu2SSQyKcdskg3dWkHJ6AViWwXTUPXwkUq6EWqHw4nNDPy");

//        Bip32Node NODE1 = Bip32Node.decode("tpubD6NzVbkrYhZ4X4tuLUxPpCvAZBWXW2mju3nt7RzmczFVYuLqieUacCQ2snjhu8zE9EagGrkG5gxthYUFhpgYx4bEVM9dYraywwZHycJNg5d");
//        Bip32Node NODE2 = Bip32Node.decode("tpubD6NzVbkrYhZ4WpEJatAtP8DJjva1o2cbV8LbnhLNLrMCaUmF3rUMRFgsVneh53JipwKMwUp3QGGzqff7avf5M9QLR7RZaEy3ha5ihtrkDRQ");
//        Bip32Node NODE3 = Bip32Node.decode("tpubD6NzVbkrYhZ4XDJNtex5Gm1Hzj4bbewgop6stxxZv4trZ3YFdLk1p9VvzQtYTk8Lf4BMo5pWA5TwJGgFbbEFaBr5Ft951V2wYQcMLWQNji4");
//

        MultiSignAddressGenerator generator = new MultiSignAddressGenerator();
        ECKey ecKey1 = NODE1.getChild(44).getChild(1).getChild(1).getChild(1).getChild(0).getEcKey();
        ECKey ecKey2 = NODE2.getChild(44).getChild(1).getChild(1).getChild(1).getChild(0).getEcKey();
        ECKey ecKey3 = NODE3.getChild(44).getChild(1).getChild(1).getChild(1).getChild(0).getEcKey();
        generator.addECKey(ecKey1);
//        System.out.println(ecKey1.getPrivKey());
        generator.addECKey(ecKey2);
//        System.out.println(ecKey2.getPrivKey());
        generator.addECKey(ecKey3);
        //tb1qn5w4kf3z60pha7ttsmww58dk4u7ftujq6zgfhmqqav4fxekfdufq3tngr9  0.00022
        String address = generator.generateAddress(TestNet3Params.get(), 2);
        System.out.println(address);
        System.out.println(address.length());


    }
}
