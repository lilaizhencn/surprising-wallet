package com.surprising.wallet.job.deposit;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.BestBlockHeight;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import com.surprising.wallet.wallet.service.TransactionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * UTXO 链区块扫描器。
 * <p>
 * 按区块逐块扫描链上交易，将充值交易写入数据库并推进扫描高度。
 * 由 {@link UtxoDepositScanJob} 调度，每次传入一条链标识（BTC/BCH/LTC/DOGE）。
 *
 * @author atomex
 */
@Slf4j
@Component
public class ScanBlockJob {

    /** 当前链运行时元数据。 */
    private AssetRuntimeMetadata currency;

    /** 交易服务，负责入库交易记录。 */
    @Autowired
    private TransactionService txService;
    /** 数据仓储服务。 */
    @Autowired
    private ChainJdbcRepository chainJdbcRepository;
    /** 链运行时服务，查询链上区块与状态。 */
    @Autowired
    private BlockchainRuntimeService blockchainRuntimeService;
    /** 运行开关/扫描参数配置服务。 */
    @Autowired
    private WalletRuntimeConfigService runtimeConfigService;

    /**
     * 按指定链扫描区块：处理区块内相关交易并推进数据库扫描高度。
     */
    public void scan(String chain) {
        if (!runtimeConfigService.isTaskEnabled(chain, WalletRuntimeConfigService.TASK_SCAN)) {
            log.debug("{} scan skipped: DB scan switch disabled", chain);
            return;
        }
        currency = blockchainRuntimeService.assetMetadata(chain);
        log.info("扫描 {} 交易 开始", requireCurrency().getName());
        Long bestHeight = null;
        try {
            blockchainRuntimeService.updateTransactionConfirmations(requireCurrency());
            bestHeight = blockchainRuntimeService.bestHeight(requireCurrency());
            BestBlockHeight storedHeight = getDbBestBlockHeight();

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

            long scanBegin = Math.max(0L, storedHeight.getHeight() + 1L);
            long scanEnd = bestHeight;
            long maxBlocksPerRun = runtimeConfigService.scanMaxBlocksPerRun(requireCurrency());
            if (maxBlocksPerRun > 0) {
                scanEnd = Math.min(scanEnd, scanBegin + maxBlocksPerRun - 1L);
            }
            long lastScannedHeight = storedHeight.getHeight();
            for (long begin = scanBegin; begin <= scanEnd; begin++) {
                List<TransactionDTO> transactions =
                        blockchainRuntimeService.findRelatedTransactions(requireCurrency(), begin);
                if (transactions == null) {
                    break;
                }
                lastScannedHeight = begin;
                if (transactions.size() == 0) {
                    continue;
                }
                txService.saveTransaction(transactions);
            }

            updateStoreHeight(lastScannedHeight, storedHeight);
            bestHeight = lastScannedHeight;
            blockchainRuntimeService.updateTotalBalance(requireCurrency());

        } catch (Throwable e) {
            log.info("扫描 {} 交易高度异常 当前高度:{} error", requireCurrency().getName(), bestHeight, e);
        }
        log.info("扫描 {} 交易高度结束 当前高度:{}", requireCurrency().getName(), bestHeight);
    }

    /**
     * 获取当前扫描上下文的链元数据，否则抛出非法状态异常。
     */
    private AssetRuntimeMetadata requireCurrency() {
        if (currency == null) {
            throw new IllegalStateException("scan job runtime currency is not configured");
        }
        return currency;
    }

    /**
     * 将扫描高度持久化到数据库。
     */
    private void updateStoreHeight(Long bestHeight, BestBlockHeight storedHeight) {
        storedHeight.setHeight(bestHeight);
        storedHeight.setUpdateDate(Date.from(Instant.now()));
        String chain = blockchainRuntimeService.chainName(requireCurrency());
        long safeHeight = Math.max(0L, bestHeight);
        chainJdbcRepository.updateScanHeight(
                chain, blockchainRuntimeService.scannerName(requireCurrency()), bestHeight, safeHeight);
    }

    /**
     * 查询数据库里的扫描快照（若不存在返回 null）。
     */
    private BestBlockHeight getDbBestBlockHeight() {
        String chain = blockchainRuntimeService.chainName(requireCurrency());
        Optional<Long> scanHeight =
                chainJdbcRepository.findScanSafeHeight(chain, blockchainRuntimeService.scannerName(requireCurrency()));
        if (scanHeight.isPresent()) {
            BestBlockHeight checkpoint = new BestBlockHeight();
            checkpoint.setCurrency(requireCurrency().getIndex());
            checkpoint.setHeight(scanHeight.get());
            checkpoint.setUpdateDate(Date.from(Instant.now()));
            return checkpoint;
        }
        return null;
    }

    /**
     * 首次初始化扫描高度，优先使用配置起始高度。
     */
    private boolean initCurrencyBestHeight(BestBlockHeight storedHeight, Long bestHeight) {
        long initialHeight = bestHeight;
        long configuredStartHeight = runtimeConfigService.scanStartHeight(requireCurrency());
        if (configuredStartHeight > 0) {
            initialHeight = Math.min(configuredStartHeight, bestHeight);
        }
        storedHeight.setHeight(initialHeight);
        storedHeight.setCurrency(requireCurrency().getIndex());
        chainJdbcRepository.updateScanHeight(
                blockchainRuntimeService.chainName(requireCurrency()),
                blockchainRuntimeService.scannerName(requireCurrency()),
                initialHeight, initialHeight);
        return true;
    }
}
