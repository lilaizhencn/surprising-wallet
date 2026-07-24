package com.surprising.wallet.sig.second.impl;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sig.second.BipNodeUtil;
import com.surprising.wallet.sig.second.ISignService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.math.BigDecimal;

/**
 * ERC20 代币第二次签名服务。
 *
 * <p>与 {@link EthSecondSignService} 同属 ETH 链，但专门处理 ERC20 代币转账。
 * 通过 {@link #supports(AssetRuntimeMetadata)} 排除 ETH 主币，
 * 只匹配有合约地址的 ERC20 资产。签名时构造 token transfer 函数调用并编码入交易 data。
 *
 * @author lilaizhen
 */
@Component
@Slf4j
public class Erc20SecondSignService extends AbstractEthLikeSecondSign implements ISignService {
    /** @return 链名称 ETH */
    @Override
    public String chain() {
        return "ETH";
    }

    /** @return "*" 通配，匹配所有非 ETH 主币的资产 */
    @Override
    public String assetSymbol() {
        return "*";
    }

    /**
     * 匹配规则：链为 ETH、非主币、且合约地址非空。
     *
     * @param asset 资产元数据
     * @return true 如果是以太坊上的 ERC20 代币
     */
    @Override
    public boolean supports(AssetRuntimeMetadata asset) {
        return asset != null
                && chain().equalsIgnoreCase(asset.chain())
                && !chain().equalsIgnoreCase(asset.assetSymbol())
                && !asset.getContractAddress().isBlank();
    }

    /**
     * 对 ERC20 代币提现交易执行第二次签名。
     *
     * <p>将代币数量转为最小单位后构造 ERC20 transfer 调用，
     * 使用 BIP32 派生的私钥签名，支持 chainId。
     *
     * @param transaction 提现交易
     * @return 签名后的交易十六进制字符串
     */
    @Override
    public String signTransaction(WithdrawTransaction transaction) {
        AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromTransaction(transaction);
        String sigStr = transaction.getSignature();
        JSONObject sigJson = JSONObject.parseObject(sigStr);
        BigDecimal feeDecimal = feeDecimal(sigJson, currency);
        Address address = sigJson.getJSONObject("address").toJavaObject(Address.class);
        Bip32Node node = BipNodeUtil.getBipNODE(address, currency);
        String signResult = tokenTransaction(
                sigJson.getBigDecimal("gasPrice").multiply(feeDecimal).toBigInteger(),
                sigJson.getBigDecimal("gas").multiply(feeDecimal).toBigInteger(),
                BigInteger.valueOf(address.getNonce()),
                node.getEcKey().getPrivateKeyAsHex(),
                currency.getContractAddress(),
                sigJson.getString("to"),
                transaction.getBalance().multiply(currency.getDecimal()).toBigInteger(),
                sigJson.containsKey("chainId") ? sigJson.getLongValue("chainId") : org.web3j.tx.ChainIdLong.NONE);
        return signResult;
    }
}
