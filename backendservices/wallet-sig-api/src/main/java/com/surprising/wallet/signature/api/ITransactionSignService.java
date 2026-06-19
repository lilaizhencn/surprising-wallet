package com.surprising.wallet.signature.api;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * @author lilaizhen
 * @create 2018-11-27 下午4:45
 **/
@FeignClient(name = "wallet-sig2", path = "/sign")
public interface ITransactionSignService {

    /**
     * 返回二次签名结果
     */
    @PostMapping("/transaction")
    String signTransaction(@RequestBody WithdrawTransaction transaction);

    /**
     * 生成需要的地址
     */
    @PostMapping("/need-address")
    List<String> generateNeedAddress(@RequestBody JSONObject param);
}
