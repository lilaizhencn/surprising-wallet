package com.surprising.wallet.account.repository;

import com.surprising.wallet.common.chain.ChainAddressRecord;
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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped persistence and outbox for EIP-7702 collection batches. */
@Repository
public class Evm7702CollectionRepository {
    private final JdbcTemplate jdbc;    public Evm7702CollectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void createAccountProjection(UUID tenantId, UUID custodyAddressId, String chain,
                                        String network, String authorityAddress) {
        jdbc.update("""
                insert into evm_7702_account(
                    id, tenant_id, custody_address_id, chain, network, authority_address)
                values (?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, custody_address_id, chain) do nothing
                """, UUID.randomUUID(), tenantId, custodyAddressId, chain, network, authorityAddress);
    }
    public Optional<RuntimeConfig> findRuntimeConfig(String chain, String network, String status) {
        return jdbc.query("""
                        select c.id, c.chain, c.network, c.chain_id, c.version,
                               c.delegate_address, c.delegate_code_hash,
                               c.collector_address, c.collector_code_hash,
                               c.payout_delegate_address, c.payout_delegate_code_hash,
                               c.relayer_address, c.status, c.max_batch_items,
                               c.max_batch_gas, c.block_gas_ratio, c.gas_limit_multiplier,
                               c.signature_ttl_seconds, c.required_confirmations,
                               c.native_collection_enabled, c.batch_withdrawal_enabled,
                               c.withdrawal_max_wait_ms, c.withdrawal_max_batch_items,
                               ca.id relayer_chain_address_id, ca.asset_symbol,
                               ca.account_id, ca.user_id, ca.biz, ca.address_index,
                               ca.address, ca.owner_address, ca.derivation_path,
                               ca.wallet_role, ca.enabled
                          from evm_7702_config c
                          join chain_address ca on ca.id = c.relayer_chain_address_id
                         where c.chain = ? and c.network = ? and c.status = ?
                        """, (rs, rowNum) -> mapConfig(rs), chain, network, status)
                .stream().findFirst();
    }
    public RuntimeConfig requireActiveConfig(String chain, String network) {
        return findRuntimeConfig(chain, network, "ACTIVE")
                .orElseThrow(() -> new IllegalStateException(
                        "EIP-7702 ACTIVE configuration is missing for " + chain + "/" + network));
    }
    public RuntimeConfig requireRuntimeConfigVersion(String chain, String network, int version) {
        return jdbc.query("""
                        select c.id, c.chain, c.network, c.chain_id, c.version,
                               c.delegate_address, c.delegate_code_hash,
                               c.collector_address, c.collector_code_hash,
                               c.payout_delegate_address, c.payout_delegate_code_hash,
                               c.relayer_address, c.status, c.max_batch_items,
                               c.max_batch_gas, c.block_gas_ratio, c.gas_limit_multiplier,
                               c.signature_ttl_seconds, c.required_confirmations,
                               c.native_collection_enabled, c.batch_withdrawal_enabled,
                               c.withdrawal_max_wait_ms, c.withdrawal_max_batch_items,
                               ca.id relayer_chain_address_id, ca.asset_symbol,
                               ca.account_id, ca.user_id, ca.biz, ca.address_index,
                               ca.address, ca.owner_address, ca.derivation_path,
                               ca.wallet_role, ca.enabled
                          from evm_7702_config c
                          join chain_address ca on ca.id = c.relayer_chain_address_id
                         where c.chain = ? and c.network = ? and c.version = ?
                        """, (rs, rowNum) -> mapConfig(rs), chain, network, version)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException(
                        "EIP-7702 configuration version is missing for "
                                + chain + "/" + network + "/" + version));
    }
    public List<RuntimeTarget> listRuntimeTargets() {
        return jdbc.query("""
                select p.chain, p.network,
                       exists(select 1 from evm_7702_config active
                               where active.chain = p.chain and active.network = p.network
                                 and active.status = 'ACTIVE') active
                  from chain_profile p
                 where p.enabled = true and lower(p.family) = 'evm'
                   and (
                     exists(select 1 from evm_7702_config c
                             where c.chain = p.chain and c.network = p.network
                               and c.status in ('ACTIVE', 'PAUSED'))
                     or exists(select 1 from evm_collection_batch b
                                where b.chain = p.chain and b.network = p.network
                                  and b.status in ('BROADCAST_UNKNOWN', 'SUBMITTED', 'CONFIRMING'))
                   )
                 order by p.chain, p.network
                """, (rs, rowNum) -> new RuntimeTarget(
                rs.getString("chain"), rs.getString("network"), rs.getBoolean("active")));
    }
    public List<UnknownAttempt> listUnknownAttempts(String chain, String network, int limit) {
        return jdbc.query("""
                select b.tenant_id, b.id batch_id, a.tx_hash,
                       a.signed_tx_ciphertext, a.rebroadcast_count
                  from evm_collection_batch b
                  join evm_collection_batch_attempt a
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
                update evm_collection_batch_attempt
                   set rebroadcast_count = rebroadcast_count + 1,
                       last_rebroadcast_at = now(), error_code = 'REBROADCASTING',
                       error_message = 'resubmitting the persisted raw transaction'
                 where tenant_id = ? and batch_id = ? and tx_hash = ? and status = 'UNKNOWN'
                """, attempt.tenantId(), attempt.batchId(), attempt.txHash());
    }
    public void markRecoveryError(UnknownAttempt attempt, String errorCode, String errorMessage) {
        jdbc.update("""
                update evm_collection_batch_attempt
                   set error_code = ?, error_message = ?
                 where tenant_id = ? and batch_id = ? and tx_hash = ? and status = 'UNKNOWN'
                """, errorCode, truncate(errorMessage, 1000), attempt.tenantId(),
                attempt.batchId(), attempt.txHash());
        jdbc.update("""
                update evm_collection_batch
                   set error_code = ?, error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'BROADCAST_UNKNOWN'
                """, errorCode, truncate(errorMessage, 1000), attempt.tenantId(), attempt.batchId());
    }

    @Transactional(rollbackFor = Throwable.class)
    public Optional<Batch> claimNextBatch(String chain, String network) {
        RuntimeConfig config = requireActiveConfig(chain, network);
        CandidateGroup group = jdbc.query("""
                        select cr.tenant_id, cr.asset_symbol, cr.to_address,
                               case when asset.native_asset
                                   then '0x0000000000000000000000000000000000000000'
                                   else tc.contract_address end contract_address,
                               asset.decimals
                          from collection_record cr
                          join custody_address ca
                            on ca.tenant_id = cr.tenant_id
                           and ca.id = cr.custody_address_id
                           and ca.status = 'ACTIVE'
                          join evm_7702_account ea
                            on ea.tenant_id = cr.tenant_id
                           and ea.custody_address_id = cr.custody_address_id
                           and ea.chain = cr.chain
                           and ea.network = ?
                          join custody_gas_account ga
                            on ga.tenant_id = cr.tenant_id
                           and ga.chain = cr.chain
                           and ga.status = 'ACTIVE'
                          join custody_address hot
                            on hot.tenant_id = ga.tenant_id
                           and hot.id = ga.custody_address_id
                           and hot.status = 'ACTIVE'
                          join chain_asset asset
                            on asset.chain = cr.chain
                           and asset.symbol = cr.asset_symbol
                           and asset.active = true
                          left join token_config tc
                            on tc.chain = cr.chain
                           and tc.network = ?
                           and tc.symbol = cr.asset_symbol
                           and tc.enabled = true
                           and tc.collect_enabled = true
                         where cr.chain = ?
                           and cr.tenant_id is not null
                           and cr.custody_address_id is not null
                           and cr.status in ('CREATED', 'RETRYING')
                           and lower(cr.to_address) = lower(hot.address)
                           and (asset.native_asset = true or tc.id is not null)
                           and (asset.native_asset = false or ?)
                         order by cr.id
                         limit 1
                        """, (rs, rowNum) -> new CandidateGroup(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("asset_symbol"),
                        rs.getString("to_address"),
                        rs.getString("contract_address"),
                        rs.getInt("decimals")), network, network, chain,
                config.nativeCollectionEnabled())
                .stream().findFirst().orElse(null);
        if (group == null) {
            return Optional.empty();
        }

        List<ClaimedItem> items = jdbc.query("""
                        select cr.id collection_record_id, cr.collection_no,
                               cr.tenant_id, cr.custody_address_id, cr.from_address,
                               cr.to_address, cr.amount,
                               chain_address.id chain_address_id,
                               chain_address.chain native_chain,
                               chain_address.asset_symbol native_symbol,
                               chain_address.account_id, chain_address.user_id,
                               chain_address.biz, chain_address.address_index,
                               chain_address.owner_address, chain_address.derivation_path,
                               chain_address.wallet_role, chain_address.enabled
                          from collection_record cr
                          join custody_address ca
                            on ca.tenant_id = cr.tenant_id
                           and ca.id = cr.custody_address_id
                          join evm_7702_account ea
                            on ea.tenant_id = ca.tenant_id
                           and ea.custody_address_id = ca.id
                           and ea.chain = cr.chain
                           and ea.network = ?
                          join chain_address
                            on chain_address.tenant_id = ca.tenant_id
                           and chain_address.id = ca.chain_address_id
                         where cr.tenant_id = ? and cr.chain = ?
                           and cr.asset_symbol = ?
                           and lower(cr.to_address) = lower(?)
                           and cr.status in ('CREATED', 'RETRYING')
                         order by cr.id
                         limit ?
                         for update of cr skip locked
                        """, (rs, rowNum) -> mapClaimedItem(rs, group.decimals()),
                network, group.tenantId(), chain, group.assetSymbol(),
                group.hotWallet(), config.maxBatchItems());
        if (items.isEmpty()) {
            return Optional.empty();
        }
        for (ClaimedItem item : items) {
            if (!item.tenantId().equals(group.tenantId())) {
                throw new IllegalStateException("cross-tenant collection batch is forbidden");
            }
        }

        UUID batchId = UUID.randomUUID();
        String batchHash = Numeric.toHexString(Hash.sha3(
                (group.tenantId() + ":" + batchId).getBytes(StandardCharsets.UTF_8)));
        jdbc.update("""
                insert into evm_collection_batch(
                    id, tenant_id, chain, network, asset_symbol, token_contract,
                    token_decimals, hot_wallet, relayer_address, delegate_version,
                    batch_hash, status, item_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'LOCKED', ?)
                """, batchId, group.tenantId(), chain, network, group.assetSymbol(),
                group.tokenContract(), group.decimals(), group.hotWallet(),
                config.relayerAddress(), config.version(), batchHash, items.size());
        Instant signatureDeadline = Instant.now().plusSeconds(config.signatureTtlSeconds());
        for (int index = 0; index < items.size(); index++) {
            ClaimedItem item = items.get(index);
            jdbc.update("""
                    insert into evm_collection_batch_item(
                        id, tenant_id, batch_id, item_index, collection_record_id,
                        custody_address_id, authority_address, token_contract,
                        recipient, requested_amount_atomic, operation_nonce,
                        signature_deadline, call_gas_limit, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 180000, 'CREATED')
                    """, UUID.randomUUID(), group.tenantId(), batchId, index,
                    item.collectionRecordId(), item.custodyAddressId(), item.fromAddress(),
                    group.tokenContract(), group.hotWallet(), item.amountAtomic(),
                    Timestamp.from(signatureDeadline));
            if (jdbc.update("""
                    update collection_record
                       set status = 'SIGNING', updated_at = now()
                     where tenant_id = ? and id = ?
                       and status in ('CREATED', 'RETRYING')
                    """, group.tenantId(), item.collectionRecordId()) != 1) {
                throw new IllegalStateException("collection item claim lost");
            }
        }
        return Optional.of(new Batch(
                batchId, group.tenantId(), chain, network, group.assetSymbol(),
                group.tokenContract(), group.decimals(), group.hotWallet(), batchHash,
                signatureDeadline, config, List.copyOf(items)));
    }

    @Transactional(rollbackFor = Throwable.class)
    public void saveSignedAttempt(Batch batch, PreparedAttempt attempt) {
        if (!batch.tenantId().equals(attempt.tenantId()) || !batch.id().equals(attempt.batchId())) {
            throw new IllegalArgumentException("attempt tenant/batch mismatch");
        }
        if (attempt.items().size() != batch.items().size()) {
            throw new IllegalArgumentException("prepared item count mismatch");
        }
        if (jdbc.update("""
                update evm_collection_batch
                   set status = 'SIGNING', estimated_gas = ?, gas_limit = ?,
                       max_fee_per_gas = ?, max_priority_fee_per_gas = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'LOCKED'
                """, attempt.estimatedGas(), attempt.gasLimit(), attempt.maxFeePerGas(),
                attempt.maxPriorityFeePerGas(), batch.tenantId(), batch.id()) != 1) {
            throw new IllegalStateException("batch is not lock-owned for signing");
        }
        for (int index = 0; index < attempt.items().size(); index++) {
            PreparedItem item = attempt.items().get(index);
            if (item.itemIndex() != index) {
                throw new IllegalArgumentException("prepared item indexes must be contiguous");
            }
            if (jdbc.update("""
                    update evm_collection_batch_item
                       set authorization_included = ?, authorization_nonce = ?,
                           operation_nonce = ?, signature_deadline = ?,
                           call_gas_limit = ?, status = 'SIGNED', updated_at = now()
                     where tenant_id = ? and batch_id = ? and item_index = ?
                       and lower(authority_address) = lower(?) and status = 'CREATED'
                    """, item.authorizationIncluded(), item.authorizationNonce(),
                    item.operationNonce(), Timestamp.from(item.signatureDeadline()),
                    item.callGasLimit(), batch.tenantId(), batch.id(), index,
                    item.authorityAddress()) != 1) {
                throw new IllegalStateException("prepared item does not match claimed item");
            }
        }
        jdbc.update("""
                insert into evm_collection_batch_attempt(
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
                update evm_collection_batch
                   set status = 'SUBMITTED', canonical_tx_hash = ?,
                       submitted_at = coalesce(submitted_at, now()), updated_at = now()
                 where tenant_id = ? and id = ?
                   and status in ('SIGNING', 'BROADCAST_UNKNOWN', 'SUBMITTED', 'CONFIRMING')
                   and (canonical_tx_hash is null or lower(canonical_tx_hash) = lower(?))
                """, txHash, tenantId, batchId, txHash) != 1) {
            throw new IllegalStateException("batch submission transition failed");
        }
        jdbc.update("""
                update evm_collection_batch_attempt
                   set status = 'SUBMITTED', submitted_at = coalesce(submitted_at, now())
                 where tenant_id = ? and batch_id = ? and tx_hash = ?
                   and status in ('CREATED', 'UNKNOWN', 'SUBMITTED', 'PENDING')
                """, tenantId, batchId, txHash);
        jdbc.update("""
                update evm_collection_batch_item set status = 'SUBMITTED', updated_at = now()
                 where tenant_id = ? and batch_id = ? and status = 'SIGNED'
                """, tenantId, batchId);
        jdbc.update("""
                update collection_record cr
                   set status = 'SENT', tx_hash = ?, error_message = null, updated_at = now()
                  from evm_collection_batch_item item
                 where item.tenant_id = ? and item.batch_id = ?
                   and cr.tenant_id = item.tenant_id and cr.id = item.collection_record_id
                   and cr.status in ('SIGNING', 'SENT')
                """, txHash, tenantId, batchId);
    }
    public void markBroadcastUnknown(UUID tenantId, UUID batchId, String errorCode, String errorMessage) {
        jdbc.update("""
                update evm_collection_batch
                   set status = 'BROADCAST_UNKNOWN', error_code = ?, error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = 'SIGNING'
                """, errorCode, truncate(errorMessage, 1000), tenantId, batchId);
        jdbc.update("""
                update evm_collection_batch_attempt
                   set status = 'UNKNOWN', error_code = ?, error_message = ?
                 where tenant_id = ? and batch_id = ? and status = 'CREATED'
                """, errorCode, truncate(errorMessage, 1000), tenantId, batchId);
    }
    public Optional<PendingBatch> findPendingBatch(UUID tenantId, UUID batchId) {
        return jdbc.query("""
                select b.tenant_id, b.id, b.canonical_tx_hash, b.status,
                       c.required_confirmations, c.collector_address
                  from evm_collection_batch b
                  join evm_7702_config c
                    on c.chain = b.chain and c.network = b.network
                   and c.version = b.delegate_version
                 where b.tenant_id = ? and b.id = ?
                """, (rs, rowNum) -> new PendingBatch(
                rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getString("canonical_tx_hash"), rs.getString("status"),
                rs.getInt("required_confirmations"), rs.getString("collector_address")), tenantId, batchId)
                .stream().findFirst();
    }
    public List<PendingBatch> listPendingBatches(String chain, String network, int limit) {
        return jdbc.query("""
                select b.tenant_id, b.id, b.canonical_tx_hash, b.status,
                       c.required_confirmations, c.collector_address
                  from evm_collection_batch b
                  join evm_7702_config c
                    on c.chain = b.chain and c.network = b.network
                   and c.version = b.delegate_version
                 where b.chain = ? and b.network = ?
                   and b.status in ('SUBMITTED', 'CONFIRMING')
                 order by b.submitted_at, b.id
                 limit ?
                """, (rs, rowNum) -> new PendingBatch(
                rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getString("canonical_tx_hash"), rs.getString("status"),
                rs.getInt("required_confirmations"), rs.getString("collector_address")),
                chain, network, Math.min(Math.max(limit, 1), 200));
    }
    public List<BatchItemIdentity> listBatchItemIdentities(UUID tenantId, UUID batchId) {
        return jdbc.query("""
                select item_index, authority_address, token_contract, recipient,
                       requested_amount_atomic
                  from evm_collection_batch_item
                 where tenant_id = ? and batch_id = ?
                 order by item_index
                """, (rs, rowNum) -> new BatchItemIdentity(
                rs.getInt("item_index"), rs.getString("authority_address"),
                rs.getString("token_contract"), rs.getString("recipient"),
                rs.getBigDecimal("requested_amount_atomic").toBigIntegerExact()), tenantId, batchId);
    }

    @Transactional(rollbackFor = Throwable.class)
    public void completeBatch(UUID tenantId, UUID batchId, String txHash,
                              BigInteger gasUsed, BigInteger effectiveGasPrice,
                              BigInteger l2Fee, BigInteger l1Fee, BigInteger operatorFee,
                              BigInteger blockNumber, String blockHash,
                              List<com.surprising.wallet.service.chain.evm.Evm7702ReceiptParser.ItemResult> results) {
        List<BatchItemIdentity> expected = listBatchItemIdentities(tenantId, batchId);
        if (expected.size() != results.size()) {
            throw new IllegalStateException("receipt result count does not match batch");
        }
        int failures = 0;
        for (int index = 0; index < results.size(); index++) {
            BatchItemIdentity identity = expected.get(index);
            var result = results.get(index);
            if (identity.itemIndex() != result.itemIndex()
                    || !identity.authority().equalsIgnoreCase(result.authority())
                    || !identity.token().equalsIgnoreCase(result.token())
                    || !identity.recipient().equalsIgnoreCase(result.recipient())
                    || !identity.amount().equals(result.requestedAmount())) {
                throw new IllegalStateException("receipt item identity does not match persisted batch");
            }
            String itemStatus = result.success() ? "CONFIRMED" : "RETRYABLE";
            if (!result.success()) failures++;
            if (jdbc.update("""
                    update evm_collection_batch_item
                       set actual_received_atomic = ?, status = ?, log_index = ?,
                           error_hash = ?, updated_at = now()
                     where tenant_id = ? and batch_id = ? and item_index = ?
                       and status = 'SUBMITTED'
                    """, result.actualReceived(), itemStatus, result.logIndex(),
                    result.success() ? null : result.errorHash(), tenantId, batchId, index) != 1) {
                throw new IllegalStateException("batch item completion transition failed");
            }
            String collectionStatus = result.success() ? "CONFIRMED" : "RETRYING";
            jdbc.update("""
                    update collection_record cr
                       set status = ?, tx_hash = ?, error_message = ?, updated_at = now()
                      from evm_collection_batch_item item
                     where item.tenant_id = ? and item.batch_id = ? and item.item_index = ?
                       and cr.id = item.collection_record_id and cr.tenant_id = item.tenant_id
                    """, collectionStatus, txHash,
                    result.success() ? null : "EIP-7702 item execution failed: " + result.errorHash(),
                    tenantId, batchId, index);
            if (jdbc.update("""
                    update evm_7702_account account
                       set delegation_status = 'ACTIVE',
                           activation_tx_hash = case when batch.authorization_included
                               then coalesce(activation_tx_hash, ?) else activation_tx_hash end,
                           delegate_address = batch.delegate_address,
                           delegate_version = batch.delegate_version,
                           observed_operation_nonce = batch.operation_nonce + case when ? then 1 else 0 end,
                           updated_at = now()
                      from (
                        select i.custody_address_id, i.authorization_included,
                               i.operation_nonce, b.delegate_version,
                               c.delegate_address
                          from evm_collection_batch_item i
                          join evm_collection_batch b
                            on b.tenant_id = i.tenant_id and b.id = i.batch_id
                          join evm_7702_config c
                            on c.chain = b.chain and c.network = b.network
                           and c.version = b.delegate_version
                         where i.tenant_id = ? and i.batch_id = ? and i.item_index = ?
                      ) batch
                     where account.tenant_id = ?
                       and account.custody_address_id = batch.custody_address_id
                    """, txHash, result.success(), tenantId, batchId, index, tenantId) != 1) {
                throw new IllegalStateException("EIP-7702 account projection completion failed");
            }
        }
        BigInteger totalFee = l2Fee.add(l1Fee).add(operatorFee);
        BigDecimal actualFee = new BigDecimal(totalFee)
                .movePointLeft(18).stripTrailingZeros();
        String batchStatus = failures == 0 ? "CONFIRMED"
                : failures == results.size() ? "FAILED" : "PARTIAL_FAILED";
        if (jdbc.update("""
                update evm_collection_batch
                   set status = ?, actual_gas_used = ?, effective_gas_price = ?,
                       l2_fee_atomic = ?, l1_fee_atomic = ?, operator_fee_atomic = ?,
                       total_fee_atomic = ?, actual_fee = ?,
                       confirmed_block_number = ?, confirmed_block_hash = ?,
                       confirmed_at = now(), updated_at = now()
                 where tenant_id = ? and id = ? and canonical_tx_hash = ?
                   and status in ('SUBMITTED', 'CONFIRMING')
                """, batchStatus, gasUsed, effectiveGasPrice,
                l2Fee, l1Fee, operatorFee, totalFee, actualFee,
                blockNumber, blockHash, tenantId, batchId, txHash) != 1) {
            throw new IllegalStateException("batch completion transition failed");
        }
        jdbc.update("""
                update evm_collection_batch_attempt
                   set status = 'CONFIRMED', observed_at = now()
                 where tenant_id = ? and batch_id = ? and tx_hash = ?
                   and status in ('SUBMITTED', 'PENDING')
                """, tenantId, batchId, txHash);
    }

    @Transactional(rollbackFor = Throwable.class)
    public void releaseUnbroadcastBatch(Batch batch, String errorCode, String errorMessage) {
        int attempts = Optional.ofNullable(jdbc.queryForObject("""
                select count(*) from evm_collection_batch_attempt
                 where tenant_id = ? and batch_id = ?
                """, Integer.class, batch.tenantId(), batch.id())).orElse(0);
        if (attempts != 0) {
            throw new IllegalStateException("signed/outbox batch cannot be released as unbroadcast");
        }
        if (jdbc.update("""
                update evm_collection_batch
                   set status = 'FAILED', error_code = ?, error_message = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status in ('LOCKED', 'SIGNING')
                """, errorCode, truncate(errorMessage, 1000), batch.tenantId(), batch.id()) != 1) {
            throw new IllegalStateException("unbroadcast batch failure transition failed");
        }
        jdbc.update("""
                update evm_collection_batch_item item
                   set status = case when (
                         select count(*)
                           from evm_collection_batch_item history
                           join evm_collection_batch failed_batch
                             on failed_batch.tenant_id = history.tenant_id
                            and failed_batch.id = history.batch_id
                          where history.tenant_id = item.tenant_id
                            and history.collection_record_id = item.collection_record_id
                            and failed_batch.status = 'FAILED'
                       ) >= 3 then 'FAILED' else 'RETRYABLE' end,
                       error_code = ?, updated_at = now()
                 where item.tenant_id = ? and item.batch_id = ? and item.status = 'CREATED'
                """, errorCode, batch.tenantId(), batch.id());
        jdbc.update("""
                update collection_record cr
                   set status = case when item.status = 'FAILED' then 'FAILED' else 'RETRYING' end,
                       error_message = ?, updated_at = now()
                  from evm_collection_batch_item item
                 where item.tenant_id = ? and item.batch_id = ?
                   and cr.tenant_id = item.tenant_id and cr.id = item.collection_record_id
                   and cr.status = 'SIGNING'
                """, truncate(errorMessage, 1000), batch.tenantId(), batch.id());
    }
    private RuntimeConfig mapConfig(ResultSet rs) throws SQLException {
        String configuredRelayer = rs.getString("relayer_address");
        String derivedRelayer = rs.getString("address");
        if (!configuredRelayer.equalsIgnoreCase(derivedRelayer)) {
            throw new IllegalStateException("configured relayer address does not match chain_address key path");
        }
        if (!rs.getBoolean("enabled")) {
            throw new IllegalStateException("configured EIP-7702 relayer chain_address is disabled");
        }
        ChainAddressRecord relayer = ChainAddressRecord.builder()
                .id(rs.getLong("relayer_chain_address_id"))
                .chain(rs.getString("chain"))
                .assetSymbol(rs.getString("asset_symbol"))
                .accountId(rs.getString("account_id"))
                .userId(rs.getLong("user_id"))
                .biz(rs.getInt("biz"))
                .addressIndex(rs.getLong("address_index"))
                .address(derivedRelayer)
                .ownerAddress(rs.getString("owner_address"))
                .derivationPath(rs.getString("derivation_path"))
                .walletRole(rs.getString("wallet_role"))
                .enabled(true)
                .build();
        return new RuntimeConfig(
                rs.getObject("id", UUID.class), rs.getString("chain"), rs.getString("network"),
                rs.getBigDecimal("chain_id").toBigIntegerExact(), rs.getInt("version"),
                rs.getString("delegate_address"), rs.getString("delegate_code_hash"),
                rs.getString("collector_address"), rs.getString("collector_code_hash"),
                rs.getString("payout_delegate_address"), rs.getString("payout_delegate_code_hash"),
                configuredRelayer, rs.getString("status"), rs.getInt("max_batch_items"),
                rs.getLong("max_batch_gas"), rs.getBigDecimal("block_gas_ratio"),
                rs.getBigDecimal("gas_limit_multiplier"), rs.getInt("signature_ttl_seconds"),
                rs.getInt("required_confirmations"), rs.getBoolean("native_collection_enabled"),
                rs.getBoolean("batch_withdrawal_enabled"), rs.getInt("withdrawal_max_wait_ms"),
                rs.getInt("withdrawal_max_batch_items"), relayer);
    }
    private ClaimedItem mapClaimedItem(ResultSet rs, int decimals) throws SQLException {
        BigDecimal amount = rs.getBigDecimal("amount");
        BigInteger atomic;
        try {
            atomic = amount.movePointRight(decimals).toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new IllegalStateException("collection amount has more precision than token decimals", e);
        }
        if (atomic.signum() <= 0) {
            throw new IllegalStateException("collection amount must be positive");
        }
        ChainAddressRecord authority = ChainAddressRecord.builder()
                .id(rs.getLong("chain_address_id"))
                .chain(rs.getString("native_chain"))
                .assetSymbol(rs.getString("native_symbol"))
                .accountId(rs.getString("account_id"))
                .userId(rs.getLong("user_id"))
                .biz(rs.getInt("biz"))
                .addressIndex(rs.getLong("address_index"))
                .address(rs.getString("from_address"))
                .ownerAddress(rs.getString("owner_address"))
                .derivationPath(rs.getString("derivation_path"))
                .walletRole(rs.getString("wallet_role"))
                .enabled(rs.getBoolean("enabled"))
                .build();
        return new ClaimedItem(
                rs.getLong("collection_record_id"), rs.getString("collection_no"),
                rs.getObject("tenant_id", UUID.class), rs.getObject("custody_address_id", UUID.class),
                rs.getString("from_address"), rs.getString("to_address"), amount, atomic, authority);
    }
    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record RuntimeConfig(
            UUID id, String chain, String network, BigInteger chainId, int version,
            String delegateAddress, String delegateCodeHash, String collectorAddress,
            String collectorCodeHash, String payoutDelegateAddress, String payoutDelegateCodeHash,
            String relayerAddress, String status,
            int maxBatchItems, long maxBatchGas, BigDecimal blockGasRatio,
            BigDecimal gasLimitMultiplier, int signatureTtlSeconds,
            int requiredConfirmations, boolean nativeCollectionEnabled,
            boolean batchWithdrawalEnabled, int withdrawalMaxWaitMs,
            int withdrawalMaxBatchItems, ChainAddressRecord relayerChainAddress) {
    }

    private record CandidateGroup(
            UUID tenantId, String assetSymbol, String hotWallet,
            String tokenContract, int decimals) {
    }

    public record ClaimedItem(
            long collectionRecordId, String collectionNo, UUID tenantId,
            UUID custodyAddressId, String fromAddress, String toAddress,
            BigDecimal amount, BigInteger amountAtomic, ChainAddressRecord authorityChainAddress) {
    }

    public record Batch(
            UUID id, UUID tenantId, String chain, String network, String assetSymbol,
            String tokenContract, int tokenDecimals, String hotWallet, String batchHash,
            Instant signatureDeadline, RuntimeConfig config, List<ClaimedItem> items) {
    }

    public record PreparedItem(
            int itemIndex, String authorityAddress, boolean authorizationIncluded,
            BigInteger authorizationNonce, BigInteger operationNonce,
            Instant signatureDeadline, long callGasLimit) {
    }

    public record PreparedAttempt(
            UUID tenantId, UUID batchId, long estimatedGas, long gasLimit,
            BigInteger maxFeePerGas, BigInteger maxPriorityFeePerGas,
            BigInteger relayerNonce, String txHash, String calldataHash,
            String signedTxCiphertext, String encryptionKeyVersion,
            List<PreparedItem> items) {
    }

    public record PendingBatch(
            UUID tenantId, UUID batchId, String txHash, String status,
            int requiredConfirmations, String collectorAddress) {
    }

    public record UnknownAttempt(
            UUID tenantId, UUID batchId, String txHash,
            String signedTxCiphertext, int rebroadcastCount) {
    }
    public record RuntimeTarget(String chain, String network, boolean active) {
    }

    public record BatchItemIdentity(
            int itemIndex, String authority, String token, String recipient, BigInteger amount) {
    }
}
