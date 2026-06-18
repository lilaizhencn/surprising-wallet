package com.surprising.wallet.jobs.transfer;

import com.surprising.common.mybatis.pager.PageInfo;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.AccountTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.service.criteria.AccountTransactionExample;
import com.surprising.wallet.service.service.AccountTransactionService;
import com.surprising.wallet.service.wallet.impl.Erc20Wallet;
import com.surprising.wallet.service.wallet.impl.EthWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @author atomex
 */
@Component
@Slf4j
public class Erc20TransferJob {

    //存储eth数目不够的地址

    private final Set<String> addressSet = ConcurrentHashMap.newKeySet();
    @Autowired
    AccountTransactionService accountTransactionService;
    @Autowired
    Erc20Wallet erc20Wallet;
    @Autowired
    EthWallet ethWallet;
    BigDecimal minEth = new BigDecimal("0.02");

    //    @Scheduled(cron = "1 5/30 * * * ?")
    public void execute() {

        log.info("Transfer erc20 job begin");
        CurrencyEnum.ERC20_SET.parallelStream().forEach(this::transferErc20);

        //往eth余额不够的地址中转一些eth
        addressSet.parallelStream().forEach(addr -> {
            WithdrawRecord record = WithdrawRecord.builder()
                    .address(addr)
                    .balance(minEth)
                    .currency(CurrencyEnum.ETH.getIndex())
                    .build();
            ethWallet.withdraw(record);
        });

        addressSet.clear();
        log.info("Transfer erc20 job end");

    }

    public void transferErc20(CurrencyEnum currency) {
        //一次划转100个
        int pageSize = 100;
        log.info("Transfer {} job begin", currency.getName());

        AccountTransactionExample example = new AccountTransactionExample();
        Date deadline = new Date();
        example.createCriteria().andStatusEqualTo((byte) 0).andAddressNotEqualTo(erc20Wallet.getWithdrawAddress())
                .andConfirmNumGreaterThanOrEqualTo(currency.getWithdrawConfirmNum()).andBalanceGreaterThan(BigDecimal.ZERO);

        PageInfo pageInfo = new PageInfo();
        pageInfo.setPageSize(pageSize);
        pageInfo.setStartIndex(0);
        pageInfo.setSortItem("id");
        pageInfo.setSortType(PageInfo.SORT_TYPE_ASC);
        ShardTable table = ShardTable.builder().prefix(currency.getName()).build();
        List<AccountTransaction> accountTransactions = accountTransactionService.getByPage(pageInfo, example, table);
        log.info("{} transfer tx size : {}", currency.getName(), accountTransactions.size());
        Set<String> addresses = accountTransactions.parallelStream().map(AccountTransaction::getAddress).collect(Collectors.toSet());
        log.info("{} transfer address size : {}", currency.getName(), addresses.size());
        addresses.parallelStream().forEach((address) -> {
            BigDecimal ethBalance = ethWallet.getBalance(address);
            if (ethBalance.compareTo(minEth) < 0) {
                addressSet.add(address);
            } else {
                erc20Wallet.transfer(address, currency, deadline);
            }
        });
        log.info("Transfer {} job end", currency.getName());

    }


}
