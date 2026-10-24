package com.surprising.wallet.chain.hypercore;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.repository.HyperCoreBalanceSnapshotRepository;
import com.surprising.wallet.service.DepositService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 快照锁、增量计算和充值入账必须一起提交，避免并发扫描重复入账或快照领先账本。 */
@Service
@RequiredArgsConstructor
public class HyperCoreDepositService {
    private static final String CHAIN = "HYPERCORE";
    private final HyperCoreBalanceSnapshotRepository snapshots;
    private final DepositService depositService;

    /** 记录余额快照并将正向增量转为充值事件。 */
    @Transactional(rollbackFor = Throwable.class)
    public Optional<BigDecimal> recordObservedBalance(ChainAddressRecord address, String symbol,
                                                      BigDecimal observedBalance, String rawPayload) {
        BigDecimal observed = observedBalance == null
                ? BigDecimal.ZERO : observedBalance.stripTrailingZeros();
        Timestamp now = Timestamp.from(Instant.now());
        snapshots.ensure(CHAIN, symbol, address.getAccountId(), address.getAddress(), rawPayload, now);
        BigDecimal previous = snapshots.findForUpdate(CHAIN, symbol, address.getAccountId());
        snapshots.upsert(CHAIN, symbol, address.getAccountId(), address.getAddress(),
                observed, rawPayload, now);
        BigDecimal delta = observed.subtract(previous);
        if (delta.signum() <= 0 || address.getUserId() == 0L) {
            return Optional.empty();
        }
        String txHash = "HC-SNAPSHOT-" + symbol + "-" + address.getAccountId().toLowerCase()
                + "-" + System.currentTimeMillis();
        DepositEvent event = new DepositEvent(
                ChainType.HYPERCORE, symbol, txHash, "hypercore", address.getAddress(), delta,
                System.currentTimeMillis(), txHash, 1, null, rawPayload);
        depositService.recordAndCredit(event, 0L, 1, address.getAccountId());
        return Optional.of(delta);
    }

}
