package com.surprising.wallet.jobs.transfer;

import com.surprising.wallet.service.wallet.impl.EthWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 汇总已确认的 ETH 充值到归集地址。
 */
@Component
@Slf4j
public class EthTransferJob extends AbstractTransferJob {

    @Autowired
    public EthTransferJob(EthWallet ethWallet) {
        wallet = ethWallet;
    }

//    @Scheduled(cron = "5/30 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
