package com.surprising.wallet.jobs.deposit;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component public class ScanBchBlockJob extends AbstractScanUtxoBlockJob{
    @PostConstruct public void init(){currency=blockchainRuntimeService.runtimeAsset("BCH");}
    @Scheduled(cron="9/59 * * * * ?") @Override public void execute(){super.execute();}
}
