package com.surprising.wallet.jobs.transfer;

import com.surprising.wallet.service.wallet.impl.Erc20Wallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 汇总已确认的 ERC20 USDT 充值到归集地址。
 */
@Component
@Slf4j
public class Erc20TransferJob extends AbstractTransferJob {

    @Autowired
    public Erc20TransferJob(Erc20Wallet erc20Wallet) {
        wallet = erc20Wallet;
    }

//    @Scheduled(cron = "15/30 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
