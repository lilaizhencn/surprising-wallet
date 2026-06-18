package com.surprising.wallet.web.controller;

import com.alibaba.fastjson.JSONObject;
import com.surprising.commons.support.enums.SystemErrorCode;
import com.surprising.commons.support.model.ResponseResult;
import com.surprising.commons.support.util.ResultUtils;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bitcoinj.core.ECKey;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

/**
 * @author atomexcn
 */
@Slf4j
@RestController
@RequestMapping("/inner/v1/asset")
public class InnerSigController {
    @Value("${atomex.net.pubKey}")
    private String signKeyPub;
    private ECKey signKey;
    private final int count = 20;

    @PostConstruct
    public void init() {
        signKey = Bip32Node.decode(signKeyPub).getEcKey();
    }

    /**
     * 获取签名数据
     *
     * @param time
     * @param signature
     * @return
     */
    @GetMapping("/sig")
    ResponseResult<?> getSig(@RequestParam("time") Long time, @RequestParam("signature") String signature) {
        try {
            if (checkRequestValid(time, signature)) {
                final String key = Constants.WALLET_WITHDRAW_SIG_SECOND_KEY;
                final String tmp = Constants.WALLET_WITHDRAW_SIG_SECOND_TMP_KEY;
                List<String> txs = new LinkedList<>();
                int tmpCount = count;
                //把tmp队列中的数据转移
                while (REDIS.lLen(tmp) > 0) {
                    String tx = REDIS.lPop(tmp);
                    REDIS.rPush(key, tx);
                }

                while (tmpCount > 0) {
                    String tx = REDIS.rPoplPush(key, tmp);

                    if (StringUtils.isEmpty(tx)) {
                        break;
                    }
                    txs.add(tx);
                    tmpCount = tmpCount - 1;
                }
                return ResultUtils.success(txs);
            } else {
                return ResultUtils.failure("request is valid");
            }

        } catch (Throwable e) {
            log.error("getSig error", e);
            return ResultUtils.failure(SystemErrorCode.UNKNOWN_ERROR);
        }
    }

    @PostMapping("/sig")
    ResponseResult<?> postSig(@RequestBody SignDataDto<WithdrawTransaction> signDataDto) {
        log.info("SigController接收到二次签名的交易信息={}", signDataDto);
        try {
            if (ObjectUtils.isEmpty(signDataDto)) {
                return ResultUtils.failure("data is null");
            }

            if (checkRequestValid(signDataDto.getTime(), signDataDto.getSignature())) {
                final String rKey = Constants.WALLET_WITHDRAW_SIG_DONE_KEY;
                final String tmp = Constants.WALLET_WITHDRAW_SIG_SECOND_TMP_KEY;

                List<String> tmps = REDIS.lRange(tmp, 0, count);
                if (tmps.size() != signDataDto.getSignData().size()) {
                    log.error("SigController发送二次签名-TMP数据和待签名数据数量不统一");
                    return ResultUtils.failure("SigController发送二次签名-TMP数据和待签名数据数量不统一");
                }
                REDIS.del(tmp);
                signDataDto.getSignData().parallelStream().forEach((tx) -> {
                    REDIS.rPush(rKey, JSONObject.toJSONString(tx));
                });
                log.info("SigController发送二次签名的数据到second-sign服务={}", signDataDto);
                return ResultUtils.success();
            } else {
                return ResultUtils.failure("SigController校验签名日期错误，终止本次发送任务");

            }

        } catch (Throwable e) {
            log.error("postSig error", e);
            return ResultUtils.failure(SystemErrorCode.UNKNOWN_ERROR);
        }
    }

    private boolean checkRequestValid(Long time, String signature) {
        try {
            long fiveMinutes = 5 * 60 * 1000L;
            Long now = System.currentTimeMillis();
            if (Math.abs(now - time) > fiveMinutes) {
                return false;
            }
            signKey.verifyMessage(time.toString(), new String(Hex.decode(signature)));
            return true;
        } catch (Throwable e) {
            e.printStackTrace();
            log.error("checkRequestValid error,time:{},signature:{}", time, signature);
            return false;
        }
    }

    /**
     * @author atomex-team
     * @data 16/04/2018
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class SignDataDto<T> implements Serializable {
        private static final long serialVersionUID = -8398986334984357570L;
        Long time;
        String signature;
        List<T> signData;
    }
}
