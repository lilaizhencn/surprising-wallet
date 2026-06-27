package com.surprising.wallet.jobs.deposit;

import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.BestBlockHeight;
import com.surprising.wallet.service.asset.AssetRoutingService;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.TransactionService;
import com.surprising.wallet.service.wallet.AbstractWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    ChainJdbcRepository chainJdbcRepository;
    @Autowired
    AssetRoutingService assetRoutingService;
    @Autowired
    WalletRuntimeConfigService runtimeConfigService;

    public void execute() {

        if (!isScanEnabled(wallet.getCurrency())) {
            log.warn("{} scan skipped: DB scan switch disabled", wallet.getCurrency().getName());
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
            long configuredStartHeight = runtimeConfigService.scanStartHeight(wallet.getCurrency());
            if (storedHeight.getHeight() <= 0 && configuredStartHeight <= 0) {
                log.info("{} scan start-height is 0, initializing DB height to current best height {}",
                        wallet.getCurrency().getName(), bestHeight);
                updateStoreHeight(bestHeight, storedHeight);
                return;
            }

            if (storedHeight.getHeight() > bestHeight) {
                log.warn("{} 数据库高度 {} 高于链上高度 {},跳过本次扫描",
                        wallet.getCurrency().getName(), storedHeight.getHeight(), bestHeight);
                return;
            }
            if (storedHeight.getHeight().equals(bestHeight)) {
                log.info("{} 已同步到链上最新高度 {}", wallet.getCurrency().getName(), bestHeight);
                return;
            }

            //循环扫描某个阶段的区块
            long scanBegin = isDatabaseDrivenUtxo(wallet.getCurrency())
                    ? Math.max(0L, storedHeight.getHeight() + 1L)
                    : Math.max(0L, storedHeight.getHeight()
                            - wallet.getDepositConfirmationThreshold());
            long scanEnd = bestHeight;
            long maxBlocksPerRun = runtimeConfigService.scanMaxBlocksPerRun(wallet.getCurrency());
            if (maxBlocksPerRun > 0) {
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

    private boolean isScanEnabled(RuntimeAsset currency) {
        return runtimeConfigService.isTaskEnabled(currency, WalletRuntimeConfigService.TASK_SCAN);
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
        if (isDatabaseDrivenUtxo(wallet.getCurrency())) {
            String chain = chainName(wallet.getCurrency());
            // UTXO confirmations are refreshed from stored transactions; this checkpoint is the last scanned block.
            long safeHeight = Math.max(0L, bestHeight);
            chainJdbcRepository.updateScanHeight(
                    chain, scannerName(wallet.getCurrency()), bestHeight, safeHeight);
            return;
        }
        throw new IllegalStateException(
                "legacy scan-height runtime is disabled for " + wallet.getCurrency().getName());
    }

    /**
     * 查询数据库中已经扫描到的高度
     */
    protected BestBlockHeight getDbBestBlockHeight() {
        if (isDatabaseDrivenUtxo(wallet.getCurrency())) {
            RuntimeAsset currency = wallet.getCurrency();
            String chain = chainName(currency);
            Optional<Long> scanHeight =
                    chainJdbcRepository.findScanSafeHeight(chain, scannerName(currency));
            if (scanHeight.isPresent()) {
                return checkpoint(currency, scanHeight.get());
            }
            return null;
        }
        throw new IllegalStateException(
                "legacy scan-height runtime is disabled for " + wallet.getCurrency().getName());
    }

    /**
     * 初始化扫描的区块高度
     */
    protected boolean initCurrencyBestHeight(BestBlockHeight storedHeight, Long bestHeight) {
        long initialHeight = bestHeight;
        long configuredStartHeight = runtimeConfigService.scanStartHeight(wallet.getCurrency());
        if (configuredStartHeight > 0) {
            initialHeight = Math.min(configuredStartHeight, bestHeight);
        }
        storedHeight.setHeight(initialHeight);
        storedHeight.setCurrency(wallet.getCurrency().getIndex());
        if (isDatabaseDrivenUtxo(wallet.getCurrency())) {
            chainJdbcRepository.updateScanHeight(
                    chainName(wallet.getCurrency()), scannerName(wallet.getCurrency()),
                    initialHeight, initialHeight);
            return true;
        }
        throw new IllegalStateException(
                "legacy scan-height runtime is disabled for " + wallet.getCurrency().getName());
    }

    private boolean isDatabaseDrivenUtxo(RuntimeAsset currency) {
        return assetRoutingService.isBitcoinLikeRuntimeCurrency(currency);
    }

    private String chainName(RuntimeAsset currency) {
        return assetRoutingService.requireChainForRuntimeCurrencyId(currency.getIndex());
    }

    private String scannerName(RuntimeAsset currency) {
        return assetRoutingService.scannerName(currency.getIndex());
    }

    private BestBlockHeight checkpoint(RuntimeAsset currency, Long height) {
        BestBlockHeight checkpoint = new BestBlockHeight();
        checkpoint.setCurrency(currency.getIndex());
        checkpoint.setHeight(height);
        checkpoint.setUpdateDate(Date.from(Instant.now()));
        return checkpoint;
    }
}
