package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.chain.model.BestBlockHeight;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * UTXO 链区块扫描器。
 * <p>
 * 按区块逐块扫描链上交易，将充值交易写入数据库并推进扫描高度。
 * 由 {@code UtxoDepositScanJob} 调度，每次传入一条链标识（BTC/BCH/LTC/DOGE）。
 *
 * @author atomex
 */
@Slf4j
@Service
public class BitcoinLikeDepositScanService {
    /** 交易服务，负责入库交易记录。 */
    private final TransactionService txService;
    /** 数据仓储服务。 */
    private final ChainJdbcRepository chainJdbcRepository;
    /** 链运行时服务，查询链上区块与状态。 */
    private final BlockchainRuntimeService blockchainRuntimeService;
    /** 运行开关/扫描参数配置服务。 */
    private final WalletRuntimeConfigService runtimeConfigService;

    /** 构造 UTXO 链区块扫描服务。 */
    public BitcoinLikeDepositScanService(
            TransactionService txService,
            ChainJdbcRepository chainJdbcRepository,
            BlockchainRuntimeService blockchainRuntimeService,
            WalletRuntimeConfigService runtimeConfigService) {
        this.txService = txService;
        this.chainJdbcRepository = chainJdbcRepository;
        this.blockchainRuntimeService = blockchainRuntimeService;
        this.runtimeConfigService = runtimeConfigService;
    }

    /**
     * 按指定链扫描区块：处理区块内相关交易并推进数据库扫描高度。
     */
    public void scan(String chain) {
        if (!runtimeConfigService.isTaskEnabled(chain, WalletRuntimeConfigService.TASK_SCAN)) {
            log.debug("{} scan skipped: DB scan switch disabled", chain);
            return;
        }
        AssetRuntimeMetadata currency = blockchainRuntimeService.assetMetadata(chain);
        log.info("扫描 {} 交易 开始", currency.getName());
        Long bestHeight = null;
        try {
            blockchainRuntimeService.updateTransactionConfirmations(currency);
            bestHeight = blockchainRuntimeService.bestHeight(currency);
            BestBlockHeight storedHeight = getDbBestBlockHeight(currency);

            if (ObjectUtils.isEmpty(storedHeight)) {
                storedHeight = new BestBlockHeight();
                boolean insertFlag = initCurrencyBestHeight(storedHeight, bestHeight, currency);
                if (!insertFlag) {
                    return;
                }
            }
            long configuredStartHeight = runtimeConfigService.scanStartHeight(currency);
            if (storedHeight.getHeight() <= 0 && configuredStartHeight <= 0) {
                log.info("{} scan start-height is 0, initializing DB height to current best height {}",
                        currency.getName(), bestHeight);
                updateStoreHeight(bestHeight, storedHeight, currency);
                return;
            }

            if (storedHeight.getHeight() > bestHeight) {
                log.warn("{} 数据库高度 {} 高于链上高度 {},跳过本次扫描",
                        currency.getName(), storedHeight.getHeight(), bestHeight);
                return;
            }
            if (storedHeight.getHeight().equals(bestHeight)) {
                log.info("{} 已同步到链上最新高度 {}", currency.getName(), bestHeight);
                return;
            }

            long scanBegin = Math.max(0L, storedHeight.getHeight() + 1L);
            long scanEnd = bestHeight;
            long maxBlocksPerRun = runtimeConfigService.scanMaxBlocksPerRun(currency);
            if (maxBlocksPerRun > 0) {
                scanEnd = Math.min(scanEnd, scanBegin + maxBlocksPerRun - 1L);
            }
            long lastScannedHeight = storedHeight.getHeight();
            for (long begin = scanBegin; begin <= scanEnd; begin++) {
                List<TransactionDTO> transactions =
                        blockchainRuntimeService.findRelatedTransactions(currency, begin);
                if (transactions == null) {
                    break;
                }
                lastScannedHeight = begin;
                if (transactions.size() == 0) {
                    continue;
                }
                txService.saveTransaction(transactions);
            }

            updateStoreHeight(lastScannedHeight, storedHeight, currency);
            bestHeight = lastScannedHeight;
            blockchainRuntimeService.updateTotalBalance(currency);

        } catch (Throwable e) {
            log.info("扫描 {} 交易高度异常 当前高度:{} error", currency.getName(), bestHeight, e);
        }
        log.info("扫描 {} 交易高度结束 当前高度:{}", currency.getName(), bestHeight);
    }

    /**
     * 将扫描高度持久化到数据库。
     */
    private void updateStoreHeight(
            Long bestHeight, BestBlockHeight storedHeight, AssetRuntimeMetadata currency) {
        storedHeight.setHeight(bestHeight);
        storedHeight.setUpdateDate(Date.from(Instant.now()));
        String chain = blockchainRuntimeService.chainName(currency);
        long safeHeight = Math.max(0L, bestHeight);
        chainJdbcRepository.updateScanHeight(
                chain, blockchainRuntimeService.scannerName(currency), bestHeight, safeHeight);
    }

    /**
     * 查询数据库里的扫描快照（若不存在返回 null）。
     */
    private BestBlockHeight getDbBestBlockHeight(AssetRuntimeMetadata currency) {
        String chain = blockchainRuntimeService.chainName(currency);
        Optional<Long> scanHeight =
                chainJdbcRepository.findScanSafeHeight(chain, blockchainRuntimeService.scannerName(currency));
        if (scanHeight.isPresent()) {
            BestBlockHeight checkpoint = new BestBlockHeight();
            checkpoint.setCurrency(currency.getIndex());
            checkpoint.setHeight(scanHeight.get());
            checkpoint.setUpdateDate(Date.from(Instant.now()));
            return checkpoint;
        }
        return null;
    }

    /**
     * 首次初始化扫描高度，优先使用配置起始高度。
     */
    private boolean initCurrencyBestHeight(
            BestBlockHeight storedHeight, Long bestHeight, AssetRuntimeMetadata currency) {
        long initialHeight = bestHeight;
        long configuredStartHeight = runtimeConfigService.scanStartHeight(currency);
        if (configuredStartHeight > 0) {
            initialHeight = Math.min(configuredStartHeight, bestHeight);
        }
        storedHeight.setHeight(initialHeight);
        storedHeight.setCurrency(currency.getIndex());
        chainJdbcRepository.updateScanHeight(
                blockchainRuntimeService.chainName(currency),
                blockchainRuntimeService.scannerName(currency),
                initialHeight, initialHeight);
        return true;
    }
}
