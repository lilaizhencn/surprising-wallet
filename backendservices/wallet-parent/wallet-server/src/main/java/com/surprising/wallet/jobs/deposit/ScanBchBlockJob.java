package com.surprising.wallet.jobs.deposit;
import com.surprising.wallet.service.wallet.impl.BchWallet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component public class ScanBchBlockJob extends AbstractScanUtxoBlockJob{
    @Autowired public ScanBchBlockJob(BchWallet wallet){this.wallet=wallet;}
    @Scheduled(cron="9/59 * * * * ?") @Override public void execute(){super.execute();}
}
