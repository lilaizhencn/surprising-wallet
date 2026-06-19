package com.surprising.wallet.jobs.transfer;

import com.surprising.common.mybatis.pager.PageInfo;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.wallet.common.pojo.AccountTransaction;
import com.surprising.wallet.service.criteria.AccountTransactionExample;
import com.surprising.wallet.service.service.AccountTransactionService;
import com.surprising.wallet.service.wallet.AbstractWallet;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author atomex
 */
@Slf4j
@Data
abstract public class AbstractTransferJob {
    @Autowired
    AccountTransactionService accountTransactionService;

    AbstractWallet wallet;

    public void execute() {
        //一次划转100个
        int pageSize = 100;
        log.info("{} 转账/划转 任务开始 每次转{}条记录", wallet.getCurrency().getName(), pageSize);
        try {
            AccountTransactionExample example = new AccountTransactionExample();
            example.createCriteria().andStatusEqualTo((byte) 0).andAddressNotEqualTo(wallet.getWithdrawAddress())
                    .andConfirmNumGreaterThanOrEqualTo(wallet.getCurrency().getWithdrawConfirmNum()).andBalanceGreaterThan
                    (BigDecimal.ZERO);
            PageInfo pageInfo = new PageInfo();
            pageInfo.setPageSize(pageSize);
            pageInfo.setStartIndex(0);
            pageInfo.setSortItem("id");
            pageInfo.setSortType(PageInfo.SORT_TYPE_ASC);
            Date deadline = new Date();
            ShardTable table = ShardTable.builder().prefix(wallet.getCurrency().getName()).build();
            List<AccountTransaction> accountTransactions = accountTransactionService.getByPage(pageInfo, example, table);
            Set<String> addresses = accountTransactions.parallelStream().map(AccountTransaction::getAddress).collect(Collectors.toSet());
            addresses.parallelStream().forEach((address) -> wallet.transfer(address, wallet.getCurrency(), deadline));
        } catch (Throwable e) {
            log.error("{} 划转异常", wallet.getCurrency().getName(), e);

        }
        log.info("{} 转账/划转 任务结束", wallet.getCurrency().getName());

    }
}
