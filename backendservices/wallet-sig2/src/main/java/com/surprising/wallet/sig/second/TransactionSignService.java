package com.surprising.wallet.sig.second;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.signature.api.ITransactionSignService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;


/**
 * @author atomex
 **/
@Slf4j
@RestController
public class TransactionSignService implements ITransactionSignService {

    @Override
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

    @Override
    @PostMapping("/sign/need-address")
    public List<String> generateNeedAddress(@RequestBody JSONObject param) {
        log.info("generateNeedAddress begin");

        try {
            String currency = param.getString("currency");
            CurrencyEnum currencyEnum = CurrencyEnum.parseName(currency);
            ISignService signService = SignContent.getSignService(currencyEnum);
            return signService.genrateNeedAddress(param);
        } catch (Throwable e) {
            log.error("generateNeedAddress error", e);
            return null;
        }
    }
}
