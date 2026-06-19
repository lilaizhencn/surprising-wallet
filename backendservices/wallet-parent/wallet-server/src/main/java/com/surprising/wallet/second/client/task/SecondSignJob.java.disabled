package com.surprising.wallet.second.client.task;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.annotation.StartThread;
import com.surprising.wallet.common.pojo.WithdrawOrder;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.service.service.UserAssetService;
import com.surprising.wallet.service.service.WithdrawOrderService;
import com.surprising.wallet.signature.api.ITransactionSignService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author lilaizhen
 */
@Component
@StartThread
@Slf4j
public class SecondSignJob implements Runnable {

    @Autowired
    private ITransactionSignService signService;
    @Autowired
    private WithdrawOrderService withdrawOrderService;
    @Autowired
    private UserAssetService userAssetService;

    @Override
    public void run() {
        while (true) {
            try {
                log.info("------第二次签名任务开启,查询本地待签名订单------");
                List<WithdrawOrder> orders = withdrawOrderService.getPendingOrders();
                log.info("------第二次签名任务，获取到的待签名订单数={}", orders.size());
                if (!CollectionUtils.isEmpty(orders)) {
                    orders.parallelStream().forEach(order -> {
                        try {
                            WithdrawTransaction transaction = toWithdrawTransaction(order);
                            JSONObject sigJson = JSONObject.parseObject(order.getSignatureData());
                            transaction.setBalanceStr(order.getAmount().toString());
                            String secondSig = signService.signTransaction(transaction);
                            if (StringUtils.isEmpty(secondSig)) {
                                sigJson.put("valid", false);
                                withdrawOrderService.markFailed(order.getId());
                                userAssetService.unfreeze(order.getUserId(), order.getCurrency(), order.getAmount());
                                log.warn("二次签名 验证失败 order:{}", order.getId());
                            } else {
                                sigJson.put("rawTransaction", secondSig);
                                sigJson.remove("firstSignTx");
                                sigJson.put("valid", true);
                                String newSig = sigJson.toJSONString();
                                withdrawOrderService.markSigned(order.getId(), transaction.getTxId(), newSig);
                                userAssetService.deduct(order.getUserId(), order.getCurrency(), order.getAmount());
                                log.info("二次签名 完成 order:{}", order.getId());
                            }
                        } catch (Throwable e) {
                            log.error("二次签名 异常 order:{}", order.getId(), e);
                        }
                    });
                    continue;
                }
                log.info("二次签名 结束");
            } catch (Throwable e) {
                log.error("二次签名任务失败", e);
            }
            try {
                long WAIT_TIME = 10 * 1000L;
                Thread.sleep(WAIT_TIME);
            } catch (InterruptedException e) {
                log.error("二次签名任务结束失败", e);
                break;
            }
        }
    }

    private WithdrawTransaction toWithdrawTransaction(WithdrawOrder order) {
        return WithdrawTransaction.builder()
                .id(order.getId() != null ? order.getId().intValue() : null)
                .txId(order.getTxId())
                .balance(order.getAmount())
                .signature(order.getSignatureData())
                .currency(order.getCurrency())
                .status((short) 0)
                .build();
    }
}
