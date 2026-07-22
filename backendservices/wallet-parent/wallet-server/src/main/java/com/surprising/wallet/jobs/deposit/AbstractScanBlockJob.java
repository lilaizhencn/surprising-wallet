package com.surprising.wallet.jobs.deposit;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.BestBlockHeight;
import com.surprising.wallet.service.chain.BlockchainRuntimeService;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.TransactionService;
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
    protected AssetRuntimeMetadata currency;
    @Autowired
    protected TransactionService txService;
    @Autowired
    ChainJdbcRepository chainJdbcRepository;
    @Autowired
    BlockchainRuntimeService blockchainRuntimeService;
    @Autowired
    WalletRuntimeConfigService runtimeConfigService;

    public void execute() {
        String chain = chain();
        if (!runtimeConfigService.isTaskEnabled(chain, WalletRuntimeConfigService.TASK_SCAN)) {
            log.debug("{} scan skipped: DB scan switch disabled", chain);
            return;
        }
        currency = blockchainRuntimeService.assetMetadata(chain);
        log.info("扫描 {} 交易 开始", requireCurrency().getName());
        Long bestHeight = null;
        try {
            //先更新数据库中已有的交易的确认数
            blockchainRuntimeService.updateTransactionConfirmations(requireCurrency());

            //查询链上的最新区块高度
            bestHeight = blockchainRuntimeService.bestHeight(requireCurrency());

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
            long configuredStartHeight = runtimeConfigService.scanStartHeight(requireCurrency());
            if (storedHeight.getHeight() <= 0 && configuredStartHeight <= 0) {
                log.info("{} scan start-height is 0, initializing DB height to current best height {}",
                        requireCurrency().getName(), bestHeight);
                updateStoreHeight(bestHeight, storedHeight);
                return;
            }

            if (storedHeight.getHeight() > bestHeight) {
                log.warn("{} 数据库高度 {} 高于链上高度 {},跳过本次扫描",
                        requireCurrency().getName(), storedHeight.getHeight(), bestHeight);
                return;
            }
            if (storedHeight.getHeight().equals(bestHeight)) {
                log.info("{} 已同步到链上最新高度 {}", requireCurrency().getName(), bestHeight);
                return;
            }

            //循环扫描某个阶段的区块
            long scanBegin = isDatabaseDrivenUtxo(requireCurrency())
                    ? Math.max(0L, storedHeight.getHeight() + 1L)
                    : Math.max(0L, storedHeight.getHeight()
                            - blockchainRuntimeService.depositConfirmationThreshold(requireCurrency()));
            long scanEnd = bestHeight;
            long maxBlocksPerRun = runtimeConfigService.scanMaxBlocksPerRun(requireCurrency());
            if (maxBlocksPerRun > 0) {
                scanEnd = Math.min(scanEnd, scanBegin + maxBlocksPerRun - 1L);
            }
            long lastScannedHeight = storedHeight.getHeight();
            for (long begin = scanBegin; begin <= scanEnd; begin++) {

                //具体扫描区块处理逻辑
                List<TransactionDTO> transactions =
                        blockchainRuntimeService.findRelatedTransactions(requireCurrency(), begin);

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
            blockchainRuntimeService.updateTotalBalance(requireCurrency());

        } catch (Throwable e) {
            log.info("扫描 {} 交易高度异常 当前高度:{} error", requireCurrency().getName(), bestHeight, e);
        }
        log.info("扫描 {} 交易高度结束 当前高度:{}", requireCurrency().getName(), bestHeight);
    }

    protected abstract String chain();

    protected AssetRuntimeMetadata requireCurrency() {
        if (currency == null) {
            throw new IllegalStateException("scan job runtime currency is not configured");
        }
        return currency;
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
        if (isDatabaseDrivenUtxo(requireCurrency())) {
            String chain = chainName(requireCurrency());
            // UTXO confirmations are refreshed from stored transactions; this checkpoint is the last scanned block.
            long safeHeight = Math.max(0L, bestHeight);
            chainJdbcRepository.updateScanHeight(
                    chain, scannerName(requireCurrency()), bestHeight, safeHeight);
            return;
        }
        throw new IllegalStateException(
                "legacy scan-height runtime is disabled for " + requireCurrency().getName());
    }

    /**
     * 查询数据库中已经扫描到的高度
     */
    protected BestBlockHeight getDbBestBlockHeight() {
        if (isDatabaseDrivenUtxo(requireCurrency())) {
            AssetRuntimeMetadata currency = requireCurrency();
            String chain = chainName(currency);
            Optional<Long> scanHeight =
                    chainJdbcRepository.findScanSafeHeight(chain, scannerName(currency));
            if (scanHeight.isPresent()) {
                return checkpoint(currency, scanHeight.get());
            }
            return null;
        }
        throw new IllegalStateException(
                "legacy scan-height runtime is disabled for " + requireCurrency().getName());
    }

    /**
     * 初始化扫描的区块高度
     */
    protected boolean initCurrencyBestHeight(BestBlockHeight storedHeight, Long bestHeight) {
        long initialHeight = bestHeight;
        long configuredStartHeight = runtimeConfigService.scanStartHeight(requireCurrency());
        if (configuredStartHeight > 0) {
            initialHeight = Math.min(configuredStartHeight, bestHeight);
        }
        storedHeight.setHeight(initialHeight);
        storedHeight.setCurrency(requireCurrency().getIndex());
        if (isDatabaseDrivenUtxo(requireCurrency())) {
            chainJdbcRepository.updateScanHeight(
                    chainName(requireCurrency()), scannerName(requireCurrency()),
                    initialHeight, initialHeight);
            return true;
        }
        throw new IllegalStateException(
                "legacy scan-height runtime is disabled for " + requireCurrency().getName());
    }

    private boolean isDatabaseDrivenUtxo(AssetRuntimeMetadata currency) {
        return blockchainRuntimeService.isBitcoinLikeRuntime(currency);
    }

    private String chainName(AssetRuntimeMetadata currency) {
        return blockchainRuntimeService.chainName(currency);
    }

    private String scannerName(AssetRuntimeMetadata currency) {
        return blockchainRuntimeService.scannerName(currency);
    }

    private BestBlockHeight checkpoint(AssetRuntimeMetadata currency, Long height) {
        BestBlockHeight checkpoint = new BestBlockHeight();
        checkpoint.setCurrency(currency.getIndex());
        checkpoint.setHeight(height);
        checkpoint.setUpdateDate(Date.from(Instant.now()));
        return checkpoint;
    }
}
