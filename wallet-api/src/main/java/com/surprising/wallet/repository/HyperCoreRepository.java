package com.surprising.wallet.repository;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** HyperCore 业务仓储门面，实际 SQL 由各单表仓储执行。 */
@Component
public class HyperCoreRepository {
    /** HyperCore 链标识。 */
    private static final String CHAIN = "HYPERCORE";
    /** 余额快照单表仓储。 */
    private final HyperCoreBalanceSnapshotRepository snapshots;
    /** 代币元数据单表仓储。 */
    private final HyperCoreTokenMetadataRepository tokenMetadata;
    /** 现货资产单表仓储。 */
    private final HyperCoreSpotAssetRepository spotAssets;
    /** 操作记录单表仓储。 */
    private final HyperCoreActionRepository actions;
    /** 链充值业务门面。 */
    private final ChainJdbcRepository chainRepository;

    /** 兼容测试和手工构造。 */
    public HyperCoreRepository(JdbcTemplate jdbc, ChainJdbcRepository chainRepository) {
        this(new HyperCoreBalanceSnapshotRepository(jdbc),
                new HyperCoreTokenMetadataRepository(jdbc),
                new HyperCoreSpotAssetRepository(jdbc),
                new HyperCoreActionRepository(jdbc), chainRepository);
    }

    /** Spring 构造器，注入各自负责单表的数据访问组件。 */
    @Autowired
    public HyperCoreRepository(
            HyperCoreBalanceSnapshotRepository snapshots,
            HyperCoreTokenMetadataRepository tokenMetadata,
            HyperCoreSpotAssetRepository spotAssets,
            HyperCoreActionRepository actions,
            ChainJdbcRepository chainRepository) {
        this.snapshots = snapshots;
        this.tokenMetadata = tokenMetadata;
        this.spotAssets = spotAssets;
        this.actions = actions;
        this.chainRepository = chainRepository;
    }

    /** 记录余额快照并将正向增量转为充值事件。 */
    @Transactional(rollbackFor = Throwable.class)
    public Optional<BigDecimal> recordObservedBalance(ChainAddressRecord address, String symbol,
                                                      BigDecimal observedBalance, String rawPayload) {
        BigDecimal observed = observedBalance == null
                ? BigDecimal.ZERO : observedBalance.stripTrailingZeros();
        Timestamp now = tsNow();
        snapshots.ensure(CHAIN, symbol, address.getAccountId(), address.getAddress(), rawPayload, now);
        BigDecimal previous = snapshots.findForUpdate(CHAIN, symbol, address.getAccountId());
        snapshots.upsert(CHAIN, symbol, address.getAccountId(), address.getAddress(),
                observed, rawPayload, now);
        BigDecimal delta = observed.subtract(previous);
        if (delta.signum() <= 0 || address.getUserId() == 0L) {
            return Optional.empty();
        }
        String txHash = "HC-SNAPSHOT-" + symbol + "-" + address.getAccountId().toLowerCase()
                + "-" + System.currentTimeMillis();
        DepositEvent event = new DepositEvent(
                ChainType.HYPERCORE, symbol, txHash, "hypercore", address.getAddress(), delta,
                System.currentTimeMillis(), txHash, 1, null, rawPayload);
        chainRepository.recordAndCreditDeposit(event, 0L, 1, address.getAccountId());
        return Optional.of(delta);
    }

    /** 保存 HyperCore 代币元数据。 */
    public void upsertTokenMetadata(String network, String name, Integer tokenIndex, String tokenId,
                                    Integer szDecimals, Integer weiDecimals, Boolean canonical,
                                    String evmContract, String fullName) {
        tokenMetadata.upsert(network, name, tokenIndex, tokenId, szDecimals, weiDecimals,
                canonical, evmContract, fullName, tsNow());
    }

    /** 保存 HyperCore 现货资产元数据。 */
    public void upsertSpotAsset(String network, Integer spotIndex, String name,
                                Integer baseTokenIndex, Integer quoteTokenIndex, Boolean canonical) {
        spotAssets.upsert(network, spotIndex, name, baseTokenIndex, quoteTokenIndex,
                canonical, tsNow());
    }

    /** 创建 HyperCore 链上操作记录。 */
    public void createAction(String actionId, String actionType, String assetSymbol,
                             String fromAddress, String toAddress, BigDecimal amount,
                             long nonce, String requestPayload) {
        actions.create(actionId, actionType, CHAIN, assetSymbol, fromAddress, toAddress,
                amount, nonce, requestPayload, tsNow());
    }

    /** 标记操作已接受。 */
    public void markActionAccepted(String actionId, String responsePayload) {
        actions.markAccepted(actionId, responsePayload, tsNow());
    }

    /** 标记操作失败。 */
    public void markActionFailed(String actionId, String errorMessage) {
        actions.markFailed(actionId, errorMessage, tsNow());
    }

    /** 判断操作是否已接受。 */
    public boolean actionAccepted(String actionId) {
        return actions.accepted(actionId);
    }

    /** 按名称查询 HyperCore 代币。 */
    public Optional<String> tokenNameBySymbol(String network, String symbol) {
        return tokenMetadata.findName(network, symbol);
    }

    /** 获取当前时间。 */
    private static Timestamp tsNow() {
        return Timestamp.from(Instant.now());
    }
}
