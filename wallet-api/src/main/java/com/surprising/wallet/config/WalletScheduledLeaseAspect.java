package com.surprising.wallet.config;

import com.surprising.wallet.service.WalletTaskLeaseService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 为 wallet-api 的所有定时任务增加数据库分布式租约。 */
@Aspect
@Component
public class WalletScheduledLeaseAspect {
    /** 数据库任务租约服务。 */
    private final WalletTaskLeaseService leaseService;

    /** 构造定时任务租约切面。 */
    public WalletScheduledLeaseAspect(WalletTaskLeaseService leaseService) {
        this.leaseService = leaseService;
    }

    /** 仅允许一个 wallet-api 实例执行同名定时任务，并在长任务期间自动续租。 */
    @Around("@annotation(scheduled)")
    public Object guard(ProceedingJoinPoint joinPoint, Scheduled scheduled) throws Throwable {
        String taskName = joinPoint.getSignature().getDeclaringTypeName()
                + "#" + joinPoint.getSignature().getName();
        return leaseService.execute(taskName, joinPoint::proceed);
    }
}
