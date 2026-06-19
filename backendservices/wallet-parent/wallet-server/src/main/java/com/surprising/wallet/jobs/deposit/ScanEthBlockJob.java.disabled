
import com.surprising.wallet.service.wallet.impl.EthWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author atomex
 */
@Component
@Slf4j
public class ScanEthBlockJob extends com.surprising.wallet.jobs.deposit.AbstractScanAccountBlockJob {

    @Autowired
    public ScanEthBlockJob(EthWallet ethWallet) {
        wallet = ethWallet;
    }

    //    @Scheduled(cron = "0/30 * * * * ?")
    @Override
    public void execute() {
        super.execute();
    }
}
