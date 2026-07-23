package com.surprising.wallet.job.withdraw;

import com.alibaba.fastjson.JSONObject;
import com.surprising.starters.redis.REDIS;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.chain.BlockchainRuntimeService;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * LTC 签名恢复任务。
 * <p>
 * 每 30 秒执行一次：扫描 DB 中超过 60 秒仍处于 SIGNING 状态的 LTC 交易
 * （含提现和归集），将其重新推送到 Redis 一次签名队列，防止 sig1/sig2
 * 进程异常导致交易卡在签名中间态。
 *
 * @author atomex
 */
