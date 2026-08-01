package com.surprising.wallet.repository;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * HyperCore 链专用仓储，管理 HyperCore 链的余额快照、Token 元数据、Spot 资产和链上操作记录。
 *
 * <p>HyperCore 使用余额快照模式检测充值：定期记录当前余额，与前一次快照比较，
 * 增量部分通过 {@link ChainJdbcRepository#recordAndCreditDeposit} 入账。</p>
 *
 * <p>同时也管理：</p>
 * <ul>
 *   <li>Token 元数据（hypercore_token_metadata）</li>
 *   <li>Spot 资产（hypercore_spot_asset）</li>
 *   <li>操作记录（hypercore_action_record），支持 CREATED -> ACCEPTED -> FAILED 状态流转</li>
 * </ul>
 *
 * @see ChainJdbcRepository
 */
@Repository
@RequiredArgsConstructor
public
class HyperCoreRepository {
    /** HyperCore 链标识 */
    private static final String CHAIN = "HYPERCORE";
    /**
     * 保存 {@code jdbcTemplate}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final JdbcTemplate jdbcTemplate;
    /**
     * 保存 {@code chainRepository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository chainRepository;

    /**
     * 记录或保存 {@code recordObservedBalance} 对应的数据，并遵守幂等和事务约束。
     */
    @Transactional(rollbackFor = Throwable.class)
    public Optional<BigDecimal> recordObservedBalance(ChainAddressRecord address, String symbol,
                                                      BigDecimal observedBalance, String rawPayload) {
        BigDecimal observed = observedBalance == null ? BigDecimal.ZERO : observedBalance.stripTrailingZeros();
        jdbcTemplate.update("""
                        insert into hypercore_balance_snapshot(chain, asset_symbol, account_id, address,
                                                               observed_balance, raw_payload,
                                                               observed_at, created_at, updated_at)
                        values (?, ?, ?, ?, 0, ?, ?, ?, ?)
                        on conflict (chain, asset_symbol, account_id) do nothing
                        """,
                CHAIN, symbol, address.getAccountId(), address.getAddress(), rawPayload,
                tsNow(), tsNow(), tsNow());
        List<BigDecimal> previousRows = jdbcTemplate.queryForList("""
                        select observed_balance
                          from hypercore_balance_snapshot
                         where chain = ? and asset_symbol = ? and account_id = ?
                         for update
                        """, BigDecimal.class, CHAIN, symbol, address.getAccountId());
        BigDecimal previous = previousRows.isEmpty() ? BigDecimal.ZERO : previousRows.getFirst();
        upsertBalanceSnapshot(address, symbol, observed, rawPayload);

        BigDecimal delta = observed.subtract(previous);
        if (delta.signum() <= 0 || address.getUserId() == 0L) {
            return Optional.empty();
        }
        String txHash = "HC-SNAPSHOT-" + symbol + "-" + address.getAccountId().toLowerCase()
                + "-" + System.currentTimeMillis();
        DepositEvent event = new DepositEvent(
                ChainType.HYPERCORE,
                symbol,
                txHash,
                "hypercore",
                address.getAddress(),
                delta,
                System.currentTimeMillis(),
                txHash,
                1,
                null,
                rawPayload);
        chainRepository.recordAndCreditDeposit(event, 0L, 1, address.getAccountId());
        return Optional.of(delta);
    }

    /**
     * 写入或更新 {@code upsertBalanceSnapshot} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    private void upsertBalanceSnapshot(ChainAddressRecord address, String symbol,
                                       BigDecimal observed, String rawPayload) {
        jdbcTemplate.update("""
                        insert into hypercore_balance_snapshot(chain, asset_symbol, account_id, address,
                                                               observed_balance, raw_payload,
                                                               observed_at, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (chain, asset_symbol, account_id) do update set
                            address = excluded.address,
                            observed_balance = excluded.observed_balance,
                            raw_payload = excluded.raw_payload,
                            observed_at = excluded.observed_at,
                            updated_at = excluded.updated_at
                        """,
                CHAIN, symbol, address.getAccountId(), address.getAddress(), observed, rawPayload,
                tsNow(), tsNow(), tsNow());
    }

    /**
     * 写入或更新 {@code upsertTokenMetadata} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public void upsertTokenMetadata(String network, String name, Integer tokenIndex, String tokenId,
                                    Integer szDecimals, Integer weiDecimals, Boolean canonical,
                                    String evmContract, String fullName) {
        jdbcTemplate.update("""
                        insert into hypercore_token_metadata(network, token_index, token_id, name,
                                                             sz_decimals, wei_decimals, is_canonical,
                                                             evm_contract, full_name, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (network, token_index) do update set
                            token_id = excluded.token_id,
                            name = excluded.name,
                            sz_decimals = excluded.sz_decimals,
                            wei_decimals = excluded.wei_decimals,
                            is_canonical = excluded.is_canonical,
                            evm_contract = excluded.evm_contract,
                            full_name = excluded.full_name,
                            updated_at = excluded.updated_at
                        """,
                network, tokenIndex, tokenId, name, szDecimals, weiDecimals,
                Boolean.TRUE.equals(canonical), evmContract, fullName, tsNow(), tsNow());
    }

    /**
     * 写入或更新 {@code upsertSpotAsset} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public void upsertSpotAsset(String network, Integer spotIndex, String name,
                                Integer baseTokenIndex, Integer quoteTokenIndex, Boolean canonical) {
        jdbcTemplate.update("""
                        insert into hypercore_spot_asset(network, spot_index, name, base_token_index,
                                                         quote_token_index, is_canonical, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (network, spot_index) do update set
                            name = excluded.name,
                            base_token_index = excluded.base_token_index,
                            quote_token_index = excluded.quote_token_index,
                            is_canonical = excluded.is_canonical,
                            updated_at = excluded.updated_at
                        """,
                network, spotIndex, name, baseTokenIndex, quoteTokenIndex,
                Boolean.TRUE.equals(canonical), tsNow(), tsNow());
    }

    /**
     * 构建或生成 {@code createAction} 对应的结果，并执行输入和状态校验。
     */
    public void createAction(String actionId, String actionType, String assetSymbol,
                             String fromAddress, String toAddress, BigDecimal amount,
                             long nonce, String requestPayload) {
        jdbcTemplate.update("""
                        insert into hypercore_action_record(action_id, action_type, chain, asset_symbol,
                                                            from_address, to_address, amount, nonce,
                                                            request_payload, status, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?)
                        on conflict (action_id) do nothing
                        """,
                actionId, actionType, CHAIN, assetSymbol, fromAddress, toAddress, amount, nonce,
                requestPayload, tsNow(), tsNow());
    }
    /**
     * 写入或更新 {@code markActionAccepted} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public void markActionAccepted(String actionId, String responsePayload) {
        jdbcTemplate.update("""
                        update hypercore_action_record
                           set status = 'ACCEPTED',
                               response_payload = ?,
                               error_message = null,
                               updated_at = ?
                         where action_id = ?
                        """, responsePayload, tsNow(), actionId);
    }
    /**
     * 写入或更新 {@code markActionFailed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
    public void markActionFailed(String actionId, String errorMessage) {
        jdbcTemplate.update("""
                        update hypercore_action_record
                           set status = 'FAILED',
                               error_message = ?,
                               updated_at = ?
                         where action_id = ?
                        """, errorMessage, tsNow(), actionId);
    }
    /**
     * 执行 {@code actionAccepted} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean actionAccepted(String actionId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                        select exists(
                            select 1 from hypercore_action_record
                             where action_id = ? and status = 'ACCEPTED'
                        )
                        """, Boolean.class, actionId);
        return Boolean.TRUE.equals(exists);
    }
    /**
     * 编码 {@code tokenNameBySymbol} 对应的数据，生成链上或接口所需的表示。
     */
    public Optional<String> tokenNameBySymbol(String network, String symbol) {
        List<String> values = jdbcTemplate.queryForList("""
                        select name
                          from hypercore_token_metadata
                         where network = ? and upper(name) = upper(?)
                         order by is_canonical desc, token_index
                         limit 1
                        """, String.class, network, symbol);
        return values.stream().findFirst();
    }
    /**
     * 执行 {@code tsNow} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static Timestamp tsNow() {
        return Timestamp.from(Instant.now());
    }
}
