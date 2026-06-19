package com.surprising.wallet.jobs.deposit;

import com.surprising.wallet.service.service.AccountTransactionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author atomex
 * @data 12/04/2018
 */
@Slf4j
abstract public class AbstractScanAccountBlockJob extends AbstractScanBlockJob {

    @Autowired
    protected AccountTransactionService accountTransactionService;

}
