package com.surprising.wallet.sig.second;

import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
/**
 * @author atomex
 **/
@Slf4j
@RestController
public class TransactionSignService {

    @PostMapping("/sign/transaction")
    public String signTransaction(@RequestBody WithdrawTransaction transaction) {
        log.info("签名服务 开始 币种id:{}", transaction.getCurrency());
        String sig;
        try {
            transaction.setBalance(new BigDecimal(transaction.getBalanceStr()));
            CurrencyEnum currency = CurrencyEnum.parseValue(transaction.getCurrency());
            ISignService signService = SignContent.getSignService(currency);
            sig = signService.signTransaction(transaction);
            log.info("签名服务 结束 币种id:{}", CurrencyEnum.parseValue(transaction.getCurrency()).getName());
        } catch (Throwable e) {
            sig = "";
            log.error("签名服务 异常 币种id:{}", CurrencyEnum.parseValue(transaction.getCurrency()).getName(), e);
        }
        return sig;
    }

}
