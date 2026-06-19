package com.surprising.wallet.jobs.deposit;

import com.surprising.wallet.service.service.UtxoTransactionService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author atomex
 */

abstract public class AbstractScanUtxoBlockJob extends AbstractScanBlockJob {

    @Autowired
    UtxoTransactionService utxoTransactionService;
}
