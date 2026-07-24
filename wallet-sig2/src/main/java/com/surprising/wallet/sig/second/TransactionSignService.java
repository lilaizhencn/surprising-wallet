package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
/**
 * 签名服务 REST 接口，对外暴露 /sign/transaction 端点。
 *
 * <p>接受已由 sig1 处理过的提现交易，根据资产类型路由到对应的 {@link ISignService} 实现，
 * 完成第二次签名后返回签名结果。
 *
 * @author atomex
 */
@Slf4j
@RestController
public class TransactionSignService {

    /**
     * 对提现交易执行签名。
     *
     * <p>流程：
     * <ol>
     *   <li>将 balanceStr 转为 BigDecimal</li>
     *   <li>通过 {@link AssetRuntimeMetadata#fromTransaction} 解析资产信息</li>
     *   <li>通过 {@link SignContent#getSignService} 获取匹配的签名服务</li>
     *   <li>调用签名服务的 {@link ISignService#signTransaction} 完成签名</li>
     * </ol>
     *
     * @param transaction 提现交易
     * @return 签名结果字符串，异常时返回空字符串
     */
    @PostMapping("/sign/transaction")
    public String signTransaction(@RequestBody WithdrawTransaction transaction) {
        log.info("签名服务 开始 币种id:{}", transaction.getCurrency());
        String sig;
        try {
            transaction.setBalance(new BigDecimal(transaction.getBalanceStr()));
            AssetRuntimeMetadata currency = AssetRuntimeMetadata.fromTransaction(transaction);
            ISignService signService = SignContent.getSignService(currency);
            sig = signService.signTransaction(transaction);
            log.info("签名服务 结束 币种:{}", currency.getName());
        } catch (Throwable e) {
            sig = "";
            log.error("签名服务 异常 币种id:{}", transaction.getCurrency(), e);
        }
        return sig;
    }

}
