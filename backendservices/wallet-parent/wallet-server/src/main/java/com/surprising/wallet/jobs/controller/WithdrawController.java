package com.surprising.wallet.jobs.controller;

import com.alibaba.fastjson.JSONObject;
import com.surprising.commons.support.model.ResponseResult;
import com.surprising.commons.support.util.ResultUtils;
import com.surprising.wallet.common.pojo.WithdrawOrder;
import com.surprising.wallet.service.service.UserAssetService;
import com.surprising.wallet.service.service.WithdrawOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@Slf4j
@RestController
@RequestMapping("/wallet/v1")
public class WithdrawController {

    @Autowired
    private UserAssetService userAssetService;

    @Autowired
    private WithdrawOrderService withdrawOrderService;

    @PostMapping("/withdraw")
    public ResponseResult<?> createWithdraw(@RequestBody JSONObject body) {
        Long userId = body.getLong("userId");
        Integer currency = body.getInteger("currency");
        BigDecimal amount = body.getBigDecimal("amount");
        String toAddress = body.getString("toAddress");
        String chain = body.getString("chain");
        String signatureData = body.getString("signatureData");

        if (userId == null || currency == null || amount == null || toAddress == null) {
            return ResultUtils.failure("参数不完整");
        }

        // freeze balance
        boolean frozen = userAssetService.freeze(userId, currency, amount);
        if (!frozen) {
            return ResultUtils.failure("余额不足或冻结失败");
        }

        WithdrawOrder order = WithdrawOrder.builder()
                .userId(userId).currency(currency).chain(chain)
                .toAddress(toAddress).amount(amount).fee(BigDecimal.ZERO)
                .signatureData(signatureData).status(0).build();
        withdrawOrderService.create(order);

        log.info("提现申请创建成功 userId={} currency={} amount={}", userId, currency, amount);
        return ResultUtils.success(order.getId());
    }
}
