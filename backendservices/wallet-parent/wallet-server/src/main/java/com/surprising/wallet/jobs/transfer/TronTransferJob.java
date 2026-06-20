package com.surprising.wallet.jobs.transfer;

import com.surprising.wallet.service.wallet.impl.TronWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 汇总已确认的 TRX 充值到归集地址。
 */
@Component
@Slf4j
public class TronTransferJob extends AbstractTransferJob {

    @Autowired
    public TronTransferJob(TronWallet tronWallet) {
        wallet = tronWallet;
    }

//    @Scheduled(cron = "25/30 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
