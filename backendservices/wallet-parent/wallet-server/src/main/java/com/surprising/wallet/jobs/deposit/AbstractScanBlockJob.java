package com.surprising.wallet.jobs.deposit;

import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.BestBlockHeight;
import com.surprising.wallet.service.criteria.BestBlockHeightExample;
import com.surprising.wallet.service.service.BestBlockHeightService;
import com.surprising.wallet.service.service.TransactionService;
import com.surprising.wallet.service.wallet.AbstractWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.ObjectUtils;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * @author atomex
 */
@Slf4j
abstract public class AbstractScanBlockJob {
    protected AbstractWallet wallet;
    @Autowired
    protected TransactionService txService;
    @Autowired
    BestBlockHeightService bestHeightService;

    @Value("${atomex.app.env.name}")
    private String env;

    private final CurrencyEnum run = CurrencyEnum.BTC;

    public void execute() {

            if (wallet.getCurrency() != run) {
                return;
            }
        log.info("扫描 {} 交易 开始", wallet.getCurrency().getName());
        Long bestHeight = null;
        try {
            //先更新数据库中已有的交易的确认数
            wallet.updateTXConfirmation(wallet.getCurrency());

            //查询链上的最新区块高度
            bestHeight = wallet.getBestHeight();

            //查询数据库中已经同步的区块高度
            BestBlockHeight storedHeight = getDbBestBlockHeight();

            //初始化best block height 表
            if (ObjectUtils.isEmpty(storedHeight)) {
                storedHeight = new BestBlockHeight();
                boolean insertFlag = initCurrencyBestHeight(storedHeight, bestHeight);
                if (!insertFlag) {
                    return;
                }
            }

            //判断数据库中的区块高度高于链上的高度
            if (storedHeight.getHeight() >= bestHeight) {
                log.error("{} 数据库高度高于区块高度,跳过本次扫描", wallet.getCurrency().getName());
                return;
            }

            //循环扫描某个阶段的区块
            for (long begin = storedHeight.getHeight() - wallet.getCurrency().getDepositConfirmNum(); begin <= bestHeight; begin++) {

                //具体扫描区块处理逻辑
                List<TransactionDTO> transactions = wallet.findRelatedTxs(begin);

                //当前区块没有交易数据 进入下一区块
                if (transactions == null) {
                    bestHeight = begin - 1;
                    break;
                }
                if (transactions.size() == 0) {
                    continue;
                }

                //推送查询到的交易数据
                txService.saveTransaction(transactions);
            }

            //更新数据库最新扫描完的区块
            updateStoreHeight(bestHeight, storedHeight);

            //更新币种余额
            wallet.updateTotalCurrencyBalance();

        } catch (Throwable e) {
            log.info("扫描 {} 交易高度异常 当前高度:{} error", wallet.getCurrency().getName(), bestHeight, e);
        }
        log.info("扫描 {} 交易高度结束 当前高度:{}", wallet.getCurrency().getName(), bestHeight);
    }

    /**
     * 更新数据库扫描到的高度
     *
     * @param bestHeight   区块上的高度
     * @param storedHeight 数据库的高度
     */
    protected void updateStoreHeight(Long bestHeight, BestBlockHeight storedHeight) {
        storedHeight.setHeight(bestHeight);
        storedHeight.setUpdateDate(Date.from(Instant.now()));
        bestHeightService.editById(storedHeight);
    }

    /**
     * 查询数据库中已经扫描到的高度
     */
    protected BestBlockHeight getDbBestBlockHeight() {
        BestBlockHeightExample example = new BestBlockHeightExample();
        example.createCriteria().andCurrencyEqualTo(wallet.getCurrency().getIndex());
        Optional<BestBlockHeight> oneByExample = bestHeightService.getOneByExample(example);
        return oneByExample.orElse(null);
    }

    /**
     * 初始化扫描的区块高度
     */
    protected boolean initCurrencyBestHeight(BestBlockHeight storedHeight, Long bestHeight) {
        storedHeight.setHeight(bestHeight);
        storedHeight.setCurrency(wallet.getCurrency().getIndex());
        int insertFlag = bestHeightService.add(storedHeight);
        if (insertFlag < 1) {
            log.error("init best block height fail, currency:{}", wallet.getCurrency().getName());
            return false;
        }
        return true;
    }
}
