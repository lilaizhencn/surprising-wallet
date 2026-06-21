package com.surprising.wallet.jobs.deposit;

import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.BestBlockHeight;
import com.surprising.wallet.service.criteria.BestBlockHeightExample;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.BestBlockHeightService;
import com.surprising.wallet.service.service.TransactionService;
import com.surprising.wallet.service.wallet.AbstractWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.ObjectUtils;

import java.time.Instant;
import java.util.Arrays;
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
    @Autowired
    ChainJdbcRepository chainJdbcRepository;

    @Value("${atomex.app.env.name}")
    private String env;

    @Value("${atomex.wallet.scan.enabled-currencies:btc}")
    private String enabledCurrencies;

    @Value("${atomex.wallet.scan.start-height:0}")
    private Long configuredStartHeight;

    @Value("${atomex.wallet.scan.max-blocks-per-run:0}")
    private Long maxBlocksPerRun;

    public void execute() {

        if (!isScanEnabled(wallet.getCurrency())) {
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
            if (storedHeight.getHeight() <= 0 && (configuredStartHeight == null || configuredStartHeight <= 0)) {
                log.info("{} scan start-height is 0, initializing DB height to current best height {}",
                        wallet.getCurrency().getName(), bestHeight);
                updateStoreHeight(bestHeight, storedHeight);
                return;
            }

            //判断数据库中的区块高度高于链上的高度
            if (storedHeight.getHeight() >= bestHeight) {
                log.error("{} 数据库高度高于区块高度,跳过本次扫描", wallet.getCurrency().getName());
                return;
            }

            //循环扫描某个阶段的区块
            long scanBegin = isDatabaseDrivenUtxo(wallet.getCurrency())
                    ? Math.max(0L, storedHeight.getHeight() + 1L)
                    : Math.max(0L, storedHeight.getHeight() - wallet.getCurrency().getDepositConfirmNum());
            long scanEnd = bestHeight;
            if (maxBlocksPerRun != null && maxBlocksPerRun > 0) {
                scanEnd = Math.min(scanEnd, scanBegin + maxBlocksPerRun - 1L);
            }
            long lastScannedHeight = storedHeight.getHeight();
            for (long begin = scanBegin; begin <= scanEnd; begin++) {

                //具体扫描区块处理逻辑
                List<TransactionDTO> transactions = wallet.findRelatedTxs(begin);

                //当前区块没有交易数据 进入下一区块
                if (transactions == null) {
                    break;
                }
                lastScannedHeight = begin;
                if (transactions.size() == 0) {
                    continue;
                }

                //推送查询到的交易数据
                txService.saveTransaction(transactions);
            }

            //更新数据库最新扫描完的区块
            updateStoreHeight(lastScannedHeight, storedHeight);
            bestHeight = lastScannedHeight;

            //更新币种余额
            wallet.updateTotalCurrencyBalance();

        } catch (Throwable e) {
            log.info("扫描 {} 交易高度异常 当前高度:{} error", wallet.getCurrency().getName(), bestHeight, e);
        }
        log.info("扫描 {} 交易高度结束 当前高度:{}", wallet.getCurrency().getName(), bestHeight);
    }

    private boolean isScanEnabled(CurrencyEnum currency) {
        return Arrays.stream(enabledCurrencies.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .anyMatch(item -> "*".equals(item) || item.equalsIgnoreCase(currency.getName()));
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
        if (isDatabaseDrivenUtxo(wallet.getCurrency())) {
            String chain = wallet.getCurrency().getName().toUpperCase(java.util.Locale.ROOT);
            long safeHeight = Math.max(0L, bestHeight - wallet.getCurrency().getDepositConfirmNum());
            chainJdbcRepository.updateScanHeight(
                    chain, wallet.getCurrency().getName() + "-block-scanner", bestHeight, safeHeight);
        }
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
        long initialHeight = bestHeight;
        if (configuredStartHeight != null && configuredStartHeight > 0) {
            initialHeight = Math.min(configuredStartHeight, bestHeight);
        }
        storedHeight.setHeight(initialHeight);
        storedHeight.setCurrency(wallet.getCurrency().getIndex());
        int insertFlag = bestHeightService.add(storedHeight);
        if (insertFlag < 1) {
            log.error("init best block height fail, currency:{}", wallet.getCurrency().getName());
            return false;
        }
        return true;
    }

    private boolean isDatabaseDrivenUtxo(CurrencyEnum currency) {
        return currency == CurrencyEnum.LTC || currency == CurrencyEnum.DOGE;
    }
}
