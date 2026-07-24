package com.surprising.wallet.account.repository;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.chain.evm.Evm7702PayoutReceiptParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped persistence, locking and encrypted outbox for EIP-7702 payout batches. */
@Repository
public class Evm7702WithdrawalRepository {
    private final JdbcTemplate jdbc;
    private final Evm7702CollectionRepository configRepository;
    public Evm7702WithdrawalRepository(JdbcTemplate jdbc,
                                       Evm7702CollectionRepository configRepository) {
        this.jdbc = jdbc;
        this.configRepository = configRepository;
    }

    public Evm7702CollectionRepository.RuntimeConfig requireRuntimeConfigVersion(
            String chain, String network, int version) {
        return configRepository.requireRuntimeConfigVersion(chain, network, version);
    }
    public List<RuntimeTarget> listRuntimeTargets() {
        return jdbc.query("""
                select p.chain, p.network,
                       exists(select 1 from evm_7702_config active
                               where active.chain = p.chain and active.network = p.network
                                 and active.status = 'ACTIVE'
                                 and active.batch_withdrawal_enabled = true) active
                  from chain_profile p
                 where p.enabled = true and lower(p.family) = 'evm'
                   and (
                     exists(select 1 from evm_7702_config c
                             where c.chain = p.chain and c.network = p.network
                               and c.status in ('ACTIVE', 'PAUSED')
                               and c.batch_withdrawal_enabled = true)
                     or exists(select 1 from evm_withdrawal_batch b
                                where b.chain = p.chain and b.network = p.network
                                  and b.status in ('BROADCAST_UNKNOWN', 'SUBMITTED', 'CONFIRMING'))
                   )
                 order by p.chain
                """, (rs, rowNum) -> new RuntimeTarget(
                rs.getString("chain"), rs.getString("network"), rs.getBoolean("active")));
    }

    @Transactional(rollbackFor = Throwable.class)
    public Optional<Batch> claimNextBatch(String chain, String network) {
        Evm7702CollectionRepository.RuntimeConfig config =
                configRepository.requireActiveConfig(chain, network);
        if (!config.batchWithdrawalEnabled()) return Optional.empty();
        CandidateGroup group = jdbc.query("""
                        select w.tenant_id, w.asset_symbol, w.from_address,
                               case when asset.native_asset
                                   then '0x0000000000000000000000000000000000000000'
                                   else token.contract_address end token_contract,
                               asset.decimals,
                               hot.id chain_address_id, hot.account_id, hot.user_id,
                               hot.biz, hot.address_index, hot.owner_address,
                               hot.derivation_path, hot.wallet_role, hot.enabled,
                               hot.chain hot_chain, hot.asset_symbol hot_asset_symbol
                          from withdrawal_order w
                          join custody_withdrawal custody
                            on custody.tenant_id = w.tenant_id
                           and custody.withdrawal_order_id = w.id
                          join chain_asset asset
                            on asset.chain = w.chain and asset.symbol = w.asset_symbol
                           and asset.active = true
                          left join token_config token
                            on token.chain = w.chain and token.network = ?
                           and token.symbol = w.asset_symbol and token.enabled = true
                          join chain_address hot
                            on hot.tenant_id = w.tenant_id and hot.chain = w.chain
                           and lower(hot.address) = lower(w.from_address) and hot.enabled = true
                         where w.tenant_id is not null and w.chain = ?
                           and w.status in ('FROZEN', 'RETRYING')
                           and (asset.native_asset = true or token.id is not null)
                           and exists(
                             select 1
                               from custody_gas_account gas
                               join custody_address gas_address
                                 on gas_address.tenant_id = gas.tenant_id
                                and gas_address.id = gas.custody_address_id
                              where gas.tenant_id = w.tenant_id and gas.chain = w.chain
                                and gas.status = 'ACTIVE' and gas_address.status = 'ACTIVE'
                                and lower(gas_address.address) = lower(w.from_address))
                           and (
                             w.created_at <= now() - (? * interval '1 millisecond')
                             or exists(
                               select 1 from withdrawal_order sibling
                                where sibling.tenant_id = w.tenant_id and sibling.chain = w.chain
                                  and sibling.asset_symbol = w.asset_symbol
                                  and lower(sibling.from_address) = lower(w.from_address)
                                  and sibling.status in ('FROZEN', 'RETRYING')
                                  and sibling.id <> w.id)
                           )
                         order by w.id
                         limit 1
                        """, (rs, rowNum) -> mapCandidate(rs),
                network, chain, config.withdrawalMaxWaitMs()).stream().findFirst().orElse(null);
        if (group == null) return Optional.empty();

        List<ClaimedItem> items = jdbc.query("""
                        select w.id withdrawal_order_id, w.order_no, w.to_address,
                               w.amount, w.fee, w.debit_account_id,
                               custody.id custody_withdrawal_id,
                               custody.custody_address_id
                          from withdrawal_order w
                          join custody_withdrawal custody
                            on custody.tenant_id = w.tenant_id
                           and custody.withdrawal_order_id = w.id
                         where w.tenant_id = ? and w.chain = ? and w.asset_symbol = ?
                           and lower(w.from_address) = lower(?)
                           and w.status in ('FROZEN', 'RETRYING')
                         order by w.id
                         limit ?
                         for update of w skip locked
                        """, (rs, rowNum) -> mapClaimedItem(rs, group.tenantId(), group.decimals()),
                group.tenantId(), chain, group.assetSymbol(), group.hotWallet(),
                config.withdrawalMaxBatchItems());
        if (items.isEmpty()) return Optional.empty();

        UUID batchId = UUID.randomUUID();
        String batchHash = Numeric.toHexString(Hash.sha3(
                (group.tenantId() + ":WITHDRAWAL:" + batchId).getBytes(StandardCharsets.UTF_8)));
        jdbc.update("""
                insert into evm_7702_payout_account(
                    id, tenant_id, chain, network, chain_address_id, authority_address)
                values (?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, chain) do update set
                    chain_address_id = excluded.chain_address_id,
                    authority_address = excluded.authority_address,
                    network = excluded.network,
                    updated_at = now()
                """, UUID.randomUUID(), group.tenantId(), chain, network,
                group.hotChainAddress().getId(), group.hotWallet());
        jdbc.update("""
                insert into evm_withdrawal_batch(
                    id, tenant_id, chain, network, asset_symbol, token_contract,
                    token_decimals, hot_wallet, relayer_address, delegate_version,
                    batch_hash, status, item_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'LOCKED', ?)
                """, batchId, group.tenantId(), chain, network, group.assetSymbol(),
                group.tokenContract(), group.decimals(), group.hotWallet(),
                config.relayerAddress(), config.version(), batchHash, items.size());
        Instant deadline = Instant.now().plusSeconds(config.signatureTtlSeconds());
        for (int index = 0; index < items.size(); index++) {
            ClaimedItem item = items.get(index);
            jdbc.update("""
                    insert into evm_withdrawal_batch_item(
                        id, tenant_id, batch_id, item_index, withdrawal_order_id,
                        custody_withdrawal_id, withdrawal_id_hash, recipient,
                        token_contract, requested_amount_atomic, call_gas_limit, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 120000, 'CREATED')
                    """, UUID.randomUUID(), group.tenantId(), batchId, index,
                    item.withdrawalOrderId(), item.custodyWithdrawalId(),
                    Numeric.toHexString(item.withdrawalId()),
                    item.recipient(), group.tokenContract(), item.amountAtomic());
            if (jdbc.update("""
                    update withdrawal_order
                       set status = 'SIGNING', error_message = null, updated_at = now()
                     where tenant_id = ? and id = ? and status in ('FROZEN', 'RETRYING')
                    """, group.tenantId(), item.withdrawalOrderId()) != 1) {
                throw new IllegalStateException("withdrawal item claim lost");
            }
        }
        return Optional.of(new Batch(
                batchId, group.tenantId(), chain, network, group.assetSymbol(),
                group.tokenContract(), group.decimals(), group.hotWallet(), batchHash,
                deadline, group.hotChainAddress(), config, List.copyOf(items)));
    }

    @Transactional(rollbackFor = Throwable.class)
    public void saveSignedAttempt(Batch batch, PreparedAttempt attempt) {
        if (!batch.tenantId().equals(attempt.tenantId()) || !batch.id().equals(attempt.batchId())) {
            throw new IllegalArgumentException("payout attempt tenant/batch mismatch");
        }
        if (jdbc.update("""
                update evm_withdrawal_batch
                   set status = 'SIGNING', authorization_included = ?, authorization_nonce = ?,
                       operation_nonce = ?, signature_deadline = ?, estimated_gas = ?, gas_limit = ?,
                       max_fee_per_gas = ?, max_priority_fee_per_gas = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'LOCKED'
                """, attempt.authorizationIncluded(), attempt.authorizationNonce(),
                attempt.operationNonce(), Timestamp.from(attempt.signatureDeadline()),
                attempt.estimatedGas(), attempt.gasLimit(), attempt.maxFeePerGas(),
                attempt.maxPriorityFeePerGas(), batch.tenantId(), batch.id()) != 1) {
            throw new IllegalStateException("payout batch is not lock-owned for signing");
        }
        if (jdbc.update("""
                update evm_withdrawal_batch_item
                   set status = 'SIGNED', updated_at = now()
                 where tenant_id = ? and batch_id = ? and status = 'CREATED'
                """, batch.tenantId(), batch.id()) != batch.items().size()) {
            throw new IllegalStateException("not all payout items were prepared for signing");
        }
        jdbc.update("""
                insert into evm_withdrawal_batch_attempt(
                    id, tenant_id, batch_id, attempt_no, relayer_nonce, tx_hash,
                    max_fee_per_gas, max_priority_fee_per_gas, gas_limit,
                    calldata_hash, signed_tx_ciphertext, encryption_key_version, status)
                values (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED')
                """, UUID.randomUUID(), batch.tenantId(), batch.id(), attempt.relayerNonce(),
                attempt.txHash(), attempt.maxFeePerGas(), attempt.maxPriorityFeePerGas(),
                attempt.gasLimit(), attempt.calldataHash(), attempt.signedTxCiphertext(),
                attempt.encryptionKeyVersion());
    }

    @Transactional(rollbackFor = Throwable.class)
    public void markSubmitted(UUID tenantId, UUID batchId, String txHash) {
        if (jdbc.update("""
                update evm_withdrawal_batch
                   set status = 'SUBMITTED', canonical_tx_hash = ?,
                       submitted_at = coalesce(submitted_at, now()), updated_at = now()
                 where tenant_id = ? and id = ?
                   and status in ('SIGNING', 'BROADCAST_UNKNOWN', 'SUBMITTED', 'CONFIRMING')
                   and (canonical_tx_hash is null or lower(canonical_tx_hash) = lower(?))
                """, txHash, tenantId, batchId, txHash) != 1) {
            throw new IllegalStateException("payout submission transition failed");
        }
        jdbc.update("""
                update evm_withdrawal_batch_attempt
                   set status = 'SUBMITTED', submitted_at = coalesce(submitted_at, now())
                 where tenant_id = ? and batch_id = ? and tx_hash = ?
                   and status in ('CREATED', 'UNKNOWN', 'SUBMITTED', 'PENDING')
                """, tenantId, batchId, txHash);
        jdbc.update("""
                update evm_withdrawal_batch_item set status = 'SUBMITTED', updated_at = now()
                 where tenant_id = ? and batch_id = ? and status = 'SIGNED'
                """, tenantId, batchId);
        jdbc.update("""
                update withdrawal_order w
                   set status = 'SENT', tx_hash = ?, error_message = null, updated_at = now()
                  from evm_withdrawal_batch_item item
                 where item.tenant_id = ? and item.batch_id = ?
                   and w.tenant_id = item.tenant_id and w.id = item.withdrawal_order_id
                   and w.status = 'SIGNING' and w.tx_hash is null
                """, txHash, tenantId, batchId);
    }
    public void markBroadcastUnknown(UUID tenantId, UUID batchId, String code, String message) {
        jdbc.update("""
                update evm_withdrawal_batch
                   set status = 'BROADCAST_UNKNOWN', error_code = ?, error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'SIGNING'
                """, code, truncate(message, 1000), tenantId, batchId);
        jdbc.update("""
                update evm_withdrawal_batch_attempt
                   set status = 'UNKNOWN', error_code = ?, error_message = ?
                 where tenant_id = ? and batch_id = ? and status = 'CREATED'
                """, code, truncate(message, 1000), tenantId, batchId);
    }
    public List<UnknownAttempt> listUnknownAttempts(String chain, String network, int limit) {
        return jdbc.query("""
                select b.tenant_id, b.id batch_id, a.tx_hash,
                       a.signed_tx_ciphertext, a.rebroadcast_count
                  from evm_withdrawal_batch b
                  join evm_withdrawal_batch_attempt a
                    on a.tenant_id = b.tenant_id and a.batch_id = b.id
                 where b.chain = ? and b.network = ?
                   and b.status = 'BROADCAST_UNKNOWN' and a.status = 'UNKNOWN'
                   and (a.last_rebroadcast_at is null
                        or a.last_rebroadcast_at < now() - interval '30 seconds')
                 order by coalesce(a.last_rebroadcast_at, a.created_at), b.id
                 limit ?
                """, (rs, rowNum) -> new UnknownAttempt(
                rs.getObject("tenant_id", UUID.class), rs.getObject("batch_id", UUID.class),
                rs.getString("tx_hash"), rs.getString("signed_tx_ciphertext"),
                rs.getInt("rebroadcast_count")), chain, network, Math.min(Math.max(limit, 1), 100));
    }
    public void recordRecoveryAttempt(UnknownAttempt attempt) {
        jdbc.update("""
                update evm_withdrawal_batch_attempt
                   set rebroadcast_count = rebroadcast_count + 1,
                       last_rebroadcast_at = now(), error_code = 'REBROADCASTING',
                       error_message = 'resubmitting the persisted raw transaction'
                 where tenant_id = ? and batch_id = ? and tx_hash = ? and status = 'UNKNOWN'
                """, attempt.tenantId(), attempt.batchId(), attempt.txHash());
    }
    public void markRecoveryError(UnknownAttempt attempt, String code, String message) {
        jdbc.update("""
                update evm_withdrawal_batch_attempt set error_code = ?, error_message = ?
                 where tenant_id = ? and batch_id = ? and tx_hash = ? and status = 'UNKNOWN'
                """, code, truncate(message, 1000), attempt.tenantId(), attempt.batchId(), attempt.txHash());
        jdbc.update("""
                update evm_withdrawal_batch set error_code = ?, error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'BROADCAST_UNKNOWN'
                """, code, truncate(message, 1000), attempt.tenantId(), attempt.batchId());
    }
    public List<PendingBatch> listPendingBatches(String chain, String network, int limit) {
        return jdbc.query("""
                select b.tenant_id, b.id, b.chain, b.canonical_tx_hash, b.status,
                       b.hot_wallet, c.required_confirmations
                  from evm_withdrawal_batch b
                  join evm_7702_config c
                    on c.chain = b.chain and c.network = b.network
                   and c.version = b.delegate_version
                 where b.chain = ? and b.network = ?
                   and b.status in ('SUBMITTED', 'CONFIRMING')
                 order by b.submitted_at, b.id
                 limit ?
                """, (rs, rowNum) -> new PendingBatch(
                rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getString("chain"), rs.getString("canonical_tx_hash"), rs.getString("status"),
                rs.getString("hot_wallet"), rs.getInt("required_confirmations")),
                chain, network, Math.min(Math.max(limit, 1), 200));
    }
    public List<BatchItemIdentity> listBatchItems(UUID tenantId, UUID batchId) {
        return jdbc.query("""
                select item.tenant_id, item.item_index, item.withdrawal_order_id, item.custody_withdrawal_id,
                       item.withdrawal_id_hash, item.token_contract, item.recipient,
                       item.requested_amount_atomic, item.status,
                       w.order_no, w.asset_symbol, w.amount, w.fee, w.debit_account_id
                  from evm_withdrawal_batch_item item
                  join withdrawal_order w
                    on w.tenant_id = item.tenant_id and w.id = item.withdrawal_order_id
                 where item.tenant_id = ? and item.batch_id = ?
                 order by item.item_index
                """, (rs, rowNum) -> new BatchItemIdentity(
                rs.getObject("tenant_id", UUID.class), rs.getInt("item_index"), rs.getLong("withdrawal_order_id"),
                rs.getObject("custody_withdrawal_id", UUID.class), rs.getString("order_no"),
                Numeric.hexStringToByteArray(rs.getString("withdrawal_id_hash")),
                rs.getString("token_contract"), rs.getString("recipient"),
                rs.getBigDecimal("requested_amount_atomic").toBigIntegerExact(),
                rs.getString("asset_symbol"), rs.getBigDecimal("amount"), rs.getBigDecimal("fee"),
                rs.getString("debit_account_id"), rs.getString("status")), tenantId, batchId);
    }

    public int markItemResult(UUID tenantId, UUID batchId, int itemIndex,
                              Evm7702PayoutReceiptParser.ItemResult result, String status) {
        return jdbc.update("""
                update evm_withdrawal_batch_item
                   set actual_received_atomic = ?, status = ?, log_index = ?,
                       error_hash = ?, updated_at = now()
                 where tenant_id = ? and batch_id = ? and item_index = ? and status = 'SUBMITTED'
                """, result.actualReceived(), status, result.logIndex(),
                result.success() ? null : result.errorHash(), tenantId, batchId, itemIndex);
    }
    public int countFailedAttempts(long withdrawalOrderId) {
        return Optional.ofNullable(jdbc.queryForObject("""
                select count(*) from evm_withdrawal_batch_item
                 where withdrawal_order_id = ? and status in ('RETRYABLE', 'FAILED')
                """, Integer.class, withdrawalOrderId)).orElse(0);
    }
    public int markWithdrawalRetrying(BatchItemIdentity item, String error) {
        return jdbc.update("""
                update withdrawal_order
                   set status = 'RETRYING', tx_hash = null, error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'SENT'
                """, truncate(error, 1000), item.tenantId(), item.withdrawalOrderId());
    }
    public int markWithdrawalFailed(BatchItemIdentity item, String error) {
        return jdbc.update("""
                update withdrawal_order
                   set status = 'FAILED', error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'SENT'
                """, truncate(error, 1000), item.tenantId(), item.withdrawalOrderId());
    }

    public int markRevertedItem(UUID tenantId, UUID batchId, int itemIndex, String status,
                                String errorHash) {
        return jdbc.update("""
                update evm_withdrawal_batch_item
                   set actual_received_atomic = 0, status = ?, error_code = 'OUTER_REVERTED',
                       error_hash = ?, updated_at = now()
                 where tenant_id = ? and batch_id = ? and item_index = ? and status = 'SUBMITTED'
                """, status, errorHash, tenantId, batchId, itemIndex);
    }

    @Transactional(rollbackFor = Throwable.class)
    public void completeBatchMetadata(PendingBatch batch, String txHash,
                                      BigInteger gasUsed, BigInteger effectiveGasPrice,
                                      BigInteger l2Fee, BigInteger l1Fee, BigInteger operatorFee,
                                      BigInteger blockNumber, String blockHash,
                                      int failures, int itemCount, BigInteger operationNonce,
                                      String payoutDelegateAddress) {
        BigInteger totalFee = l2Fee.add(l1Fee).add(operatorFee);
        BigDecimal actualFee = new BigDecimal(totalFee).movePointLeft(18).stripTrailingZeros();
        String status = failures == 0 ? "CONFIRMED" : failures == itemCount ? "FAILED" : "PARTIAL_FAILED";
        if (jdbc.update("""
                update evm_withdrawal_batch
                   set status = ?, actual_gas_used = ?, effective_gas_price = ?,
                       l2_fee_atomic = ?, l1_fee_atomic = ?, operator_fee_atomic = ?,
                       total_fee_atomic = ?, actual_fee = ?, confirmed_block_number = ?,
                       confirmed_block_hash = ?, confirmed_at = now(), updated_at = now()
                 where tenant_id = ? and id = ? and canonical_tx_hash = ?
                   and status in ('SUBMITTED', 'CONFIRMING')
                """, status, gasUsed, effectiveGasPrice, l2Fee, l1Fee, operatorFee,
                totalFee, actualFee, blockNumber, blockHash,
                batch.tenantId(), batch.batchId(), txHash) != 1) {
            throw new IllegalStateException("payout batch completion transition failed");
        }
        jdbc.update("""
                update evm_withdrawal_batch_attempt set status = 'CONFIRMED', observed_at = now()
                 where tenant_id = ? and batch_id = ? and tx_hash = ?
                   and status in ('SUBMITTED', 'PENDING')
                """, batch.tenantId(), batch.batchId(), txHash);
        jdbc.update("""
                update evm_7702_payout_account
                   set delegation_status = 'ACTIVE', delegate_address = ?,
                       delegate_version = batch.delegate_version,
                       activation_tx_hash = case when batch.authorization_included
                           then coalesce(activation_tx_hash, ?) else activation_tx_hash end,
                       observed_authority_nonce = case when batch.authorization_included
                           then batch.authorization_nonce + 1 else observed_authority_nonce end,
                       observed_operation_nonce = ? + 1, updated_at = now()
                  from evm_withdrawal_batch batch
                 where batch.tenant_id = ? and batch.id = ?
                   and evm_7702_payout_account.tenant_id = batch.tenant_id
                   and evm_7702_payout_account.chain = batch.chain
                """, payoutDelegateAddress, txHash, operationNonce,
                batch.tenantId(), batch.batchId());
    }

    @Transactional(rollbackFor = Throwable.class)
    public void completeRevertedBatchMetadata(PendingBatch batch, String txHash,
                                               BigInteger gasUsed, BigInteger effectiveGasPrice,
                                               BigInteger l2Fee, BigInteger l1Fee,
                                               BigInteger operatorFee, BigInteger blockNumber,
                                               String blockHash, String errorHash) {
        BigInteger totalFee = l2Fee.add(l1Fee).add(operatorFee);
        BigDecimal actualFee = new BigDecimal(totalFee).movePointLeft(18).stripTrailingZeros();
        if (jdbc.update("""
                update evm_withdrawal_batch
                   set status = 'FAILED', actual_gas_used = ?, effective_gas_price = ?,
                       l2_fee_atomic = ?, l1_fee_atomic = ?, operator_fee_atomic = ?,
                       total_fee_atomic = ?, actual_fee = ?, confirmed_block_number = ?,
                       confirmed_block_hash = ?, error_code = 'OUTER_REVERTED',
                       error_message = ?, confirmed_at = now(), updated_at = now()
                 where tenant_id = ? and id = ? and canonical_tx_hash = ?
                   and status in ('SUBMITTED', 'CONFIRMING')
                """, gasUsed, effectiveGasPrice, l2Fee, l1Fee, operatorFee, totalFee,
                actualFee, blockNumber, blockHash, errorHash,
                batch.tenantId(), batch.batchId(), txHash) != 1) {
            throw new IllegalStateException("reverted payout batch completion transition failed");
        }
        jdbc.update("""
                update evm_withdrawal_batch_attempt
                   set status = 'FAILED', error_code = 'OUTER_REVERTED',
                       error_message = ?, observed_at = now()
                 where tenant_id = ? and batch_id = ? and tx_hash = ?
                   and status in ('SUBMITTED', 'PENDING')
                """, errorHash, batch.tenantId(), batch.batchId(), txHash);
        jdbc.update("""
                update evm_7702_payout_account account
                   set delegation_status = case when payout.authorization_included
                           then 'ACTIVE' else account.delegation_status end,
                       delegate_address = case when payout.authorization_included
                           then config.payout_delegate_address else account.delegate_address end,
                       delegate_version = case when payout.authorization_included
                           then payout.delegate_version else account.delegate_version end,
                       activation_tx_hash = case when payout.authorization_included
                           then coalesce(account.activation_tx_hash, ?) else account.activation_tx_hash end,
                       observed_authority_nonce = case when payout.authorization_included
                           then payout.authorization_nonce + 1 else account.observed_authority_nonce end,
                       observed_operation_nonce = payout.operation_nonce,
                       updated_at = now()
                  from evm_withdrawal_batch payout
                  join evm_7702_config config
                    on config.chain = payout.chain and config.network = payout.network
                   and config.version = payout.delegate_version
                 where payout.tenant_id = ? and payout.id = ?
                   and account.tenant_id = payout.tenant_id and account.chain = payout.chain
                """, txHash, batch.tenantId(), batch.batchId());
    }

    @Transactional(rollbackFor = Throwable.class)
    public void releaseUnbroadcastBatch(Batch batch, String code, String message) {
        int attempts = Optional.ofNullable(jdbc.queryForObject("""
                select count(*) from evm_withdrawal_batch_attempt
                 where tenant_id = ? and batch_id = ?
                """, Integer.class, batch.tenantId(), batch.id())).orElse(0);
        if (attempts != 0) throw new IllegalStateException("signed payout batch cannot be released");
        jdbc.update("""
                update withdrawal_order w set status = 'RETRYING', error_message = ?, updated_at = now()
                  from evm_withdrawal_batch_item item
                 where item.tenant_id = ? and item.batch_id = ?
                   and w.tenant_id = item.tenant_id and w.id = item.withdrawal_order_id
                   and w.status = 'SIGNING'
                """, truncate(message, 1000), batch.tenantId(), batch.id());
        jdbc.update("""
                update evm_withdrawal_batch_item set status = 'RETRYABLE', error_code = ?, updated_at = now()
                 where tenant_id = ? and batch_id = ? and status = 'CREATED'
                """, code, batch.tenantId(), batch.id());
        jdbc.update("""
                update evm_withdrawal_batch set status = 'FAILED', error_code = ?, error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'LOCKED'
                """, code, truncate(message, 1000), batch.tenantId(), batch.id());
    }
    public BatchState requireBatchState(UUID tenantId, UUID batchId) {
        return jdbc.query("""
                select operation_nonce, delegate_version, authorization_included
                  from evm_withdrawal_batch where tenant_id = ? and id = ?
                """, (rs, rowNum) -> new BatchState(
                rs.getBigDecimal("operation_nonce").toBigIntegerExact(),
                rs.getInt("delegate_version"), rs.getBoolean("authorization_included")),
                tenantId, batchId).stream().findFirst().orElseThrow();
    }
    private CandidateGroup mapCandidate(ResultSet rs) throws SQLException {
        ChainAddressRecord hot = ChainAddressRecord.builder()
                .id(rs.getLong("chain_address_id")).chain(rs.getString("hot_chain"))
                .assetSymbol(rs.getString("hot_asset_symbol")).accountId(rs.getString("account_id"))
                .userId(rs.getLong("user_id")).biz(rs.getInt("biz"))
                .addressIndex(rs.getLong("address_index")).address(rs.getString("from_address"))
                .ownerAddress(rs.getString("owner_address")).derivationPath(rs.getString("derivation_path"))
                .walletRole(rs.getString("wallet_role")).enabled(rs.getBoolean("enabled")).build();
        return new CandidateGroup(rs.getObject("tenant_id", UUID.class), rs.getString("asset_symbol"),
                rs.getString("from_address"), rs.getString("token_contract"), rs.getInt("decimals"), hot);
    }
    private ClaimedItem mapClaimedItem(ResultSet rs, UUID tenantId, int decimals) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("amount");
        BigInteger atomic;
        try {
            atomic = amount.movePointRight(decimals).toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new IllegalStateException("withdrawal amount exceeds asset precision", e);
        }
        if (atomic.signum() <= 0) throw new IllegalStateException("withdrawal amount must be positive");
        return new ClaimedItem(rs.getLong("withdrawal_order_id"),
                rs.getObject("custody_withdrawal_id", UUID.class),
                rs.getObject("custody_address_id", UUID.class), rs.getString("order_no"),
                rs.getString("to_address"), amount, rs.getBigDecimal("fee"), atomic,
                rs.getString("debit_account_id"), Numeric.hexStringToByteArray(
                        withdrawalHash(tenantId, rs.getObject("custody_withdrawal_id", UUID.class))));
    }
    private static String withdrawalHash(UUID tenantId, UUID custodyWithdrawalId) {
        return Numeric.toHexString(Hash.sha3(
                (tenantId + ":WITHDRAWAL:" + custodyWithdrawalId).getBytes(StandardCharsets.UTF_8)));
    }
    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record CandidateGroup(UUID tenantId, String assetSymbol, String hotWallet,
                                  String tokenContract, int decimals,
                                  ChainAddressRecord hotChainAddress) { }

    public record ClaimedItem(long withdrawalOrderId, UUID custodyWithdrawalId,
                              UUID custodyAddressId, String orderNo, String recipient,
                              BigDecimal amount, BigDecimal fee, BigInteger amountAtomic,
                              String debitAccountId, byte[] withdrawalId) {
        public ClaimedItem {
            withdrawalId = withdrawalId.clone();
        }

        @Override
        public byte[] withdrawalId() {
            return withdrawalId.clone();
        }
    }

    public record Batch(UUID id, UUID tenantId, String chain, String network, String assetSymbol,
                        String tokenContract, int tokenDecimals, String hotWallet, String batchHash,
                        Instant signatureDeadline, ChainAddressRecord hotChainAddress,
                        Evm7702CollectionRepository.RuntimeConfig config,
                        List<ClaimedItem> items) { }

    public record PreparedAttempt(UUID tenantId, UUID batchId, long estimatedGas, long gasLimit,
                                  BigInteger maxFeePerGas, BigInteger maxPriorityFeePerGas,
                                  BigInteger relayerNonce, String txHash, String calldataHash,
                                  String signedTxCiphertext, String encryptionKeyVersion,
                                  boolean authorizationIncluded, BigInteger authorizationNonce,
                                  BigInteger operationNonce, Instant signatureDeadline) { }

    public record PendingBatch(UUID tenantId, UUID batchId, String chain, String txHash, String status,
                               String hotWallet, int requiredConfirmations) { }
    public record UnknownAttempt(UUID tenantId, UUID batchId, String txHash,
                                 String signedTxCiphertext, int rebroadcastCount) { }
    public record RuntimeTarget(String chain, String network, boolean active) { }
    public record BatchState(BigInteger operationNonce, int delegateVersion,
                             boolean authorizationIncluded) { }

    public record BatchItemIdentity(UUID tenantId, int itemIndex, long withdrawalOrderId,
                                    UUID custodyWithdrawalId, String orderNo,
                                    byte[] withdrawalId, String token, String recipient,
                                    BigInteger amountAtomic, String assetSymbol,
                                    BigDecimal amount, BigDecimal fee, String debitAccountId,
                                    String status) {
        public BatchItemIdentity {
            withdrawalId = withdrawalId.clone();
        }

        @Override
        public byte[] withdrawalId() {
            return withdrawalId.clone();
        }

    }
}
