package com.surprising.wallet.service;

import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 内部提现队列的原生 UTXO 订单与冻结用例；签名和广播由后续阶段处理。 */
@Service
@RequiredArgsConstructor
public class QueuedUtxoWithdrawalService {
    private final ChainProfileRepository profiles;
    private final WithdrawalOrderRepository orders;
    private final LedgerBalanceRepository balances;
    private final WalletRuntimeConfigService runtimeConfig;

    @Transactional(rollbackFor = Throwable.class)
    public boolean accept(WithdrawRecord record, UUID tenant) {
        Objects.requireNonNull(tenant, "withdrawal tenant is required");
        var profile = profiles.findAccountByRuntimeCurrency(record.getCurrency()).orElseThrow();
        String chain = profile.getChain();
        if (!Set.of("BTC", "BCH", "LTC", "DOGE").contains(chain))
            throw new IllegalArgumentException("withdrawal queue requires a native Bitcoin-like asset");
        runtimeConfig.requireTaskEnabled(chain, WalletRuntimeConfigService.TASK_WITHDRAW, "queued UTXO withdrawal");
        if (orders.find(chain, record.getWithdrawId(), tenant).isPresent()) return true;
        BigDecimal fee = record.getFee() == null ? BigDecimal.ZERO : record.getFee();
        String account = record.getUserId().toString();
        if (orders.createForTenant(tenant, record.getWithdrawId(), record.getUserId(), chain,
                profile.getNativeSymbol(), null, account, record.getAddress(), record.getBalance(), fee) == 0) {
            if (orders.find(chain, record.getWithdrawId(), tenant).isEmpty())
                throw new IllegalStateException("withdrawal reference belongs to another tenant");
            return true;
        }
        if (!balances.freeze(chain, profile.getNativeSymbol(), account, record.getBalance().add(fee), tenant)) {
            orders.updateStatus(tenant, chain, record.getWithdrawId(), "FAILED", null, null, "ledger freeze failed");
            return false;
        }
        if (orders.updateStatus(tenant, chain, record.getWithdrawId(), "FROZEN", null, null, null) != 1)
            throw new IllegalStateException("withdrawal state changed before freeze");
        return true;
    }
}
