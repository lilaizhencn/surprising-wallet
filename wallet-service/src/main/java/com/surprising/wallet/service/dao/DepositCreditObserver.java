package com.surprising.wallet.service.dao;

import com.surprising.wallet.common.chain.DepositEvent;

/**
 * Transactional extension point invoked after a deposit is idempotently credited.
 *
 * <p>Implementations run inside {@link ChainJdbcRepository#recordAndCreditDeposit}'s
 * transaction. They must be deterministic and idempotent; throwing rolls back both
 * the ledger credit and the observer write.</p>
 */
@FunctionalInterface
public interface DepositCreditObserver {
    void onDepositCredited(DepositEvent event, long logIndex, String accountId);
}
