package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.UtxoBlock;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.queue.*;
import com.surprising.wallet.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.UUID;

/** 只处理已观察到的输出；UTXO、入账、租户投影、通知和扫描游标共同提交。 */
@Service
@RequiredArgsConstructor
public class UtxoDepositService {
    public static final String CANONICAL_SCANNER = "utxo-canonical";
    private final ChainAddressRepository addresses;
    private final CustodyGasAccountRepository gasAccounts;
    private final CustodyAddressRepository custodyAddresses;
    private final UtxoRepository utxos;
    private final ChainScanBlockRepository blocks;
    private final ChainScanHeightRepository heights;
    private final DepositService deposits;
    private final WalletRuntimeConfigService runtimeConfig;
    private final PgmqClient queue;
    private final ObjectMapper mapper;

    @Transactional(rollbackFor = Throwable.class)
    public void scanBlock(AccountChainProfile profile, UtxoBlock block) {
        runtimeConfig.requireTaskEnabled(profile.getChain(), WalletRuntimeConfigService.TASK_SCAN, "UTXO block posting");
        applyBlock(profile, block);
        heights.upsert(profile.getChain(), scannerName(profile), block.height(), block.height());
    }

    @Transactional(rollbackFor = Throwable.class)
    public void reconcileBlock(AccountChainProfile profile, UtxoBlock block) {
        var observation = deposits.observeCanonicalBlock(profile.getChain(), CANONICAL_SCANNER,
                block.height(), block.hash(), block.parentHash());
        if (observation.reorg()) applyOutputs(profile, block);
    }

    @Transactional(rollbackFor = Throwable.class)
    public void refresh(AccountChainProfile profile, UtxoRepository.PendingOutput pending, int confirmations) {
        UtxoOutput observed = pending.output();
        // 与区块重组保持相同的加锁顺序：canonical block → UTXO → 充值/账本。
        var canonical = blocks.findHashForUpdate(profile.getChain(), CANONICAL_SCANNER, observed.blockHeight());
        if (canonical.isEmpty() || !canonical.getFirst().equalsIgnoreCase(observed.blockHash())) return;
        var current = utxos.lockPending(profile.getChain(), observed.txHash(), observed.vout()).orElse(null);
        if (current == null || current.blockHeight() != observed.blockHeight()
                || !current.blockHash().equalsIgnoreCase(observed.blockHash())) return;
        int count = Math.max(0, confirmations);
        utxos.updateConfirmations(profile.getChain(), current.txHash(), current.vout(), count);
        post(profile, current.withConfirmations(count));
    }

    private void applyBlock(AccountChainProfile profile, UtxoBlock block) {
        deposits.observeCanonicalBlock(profile.getChain(), CANONICAL_SCANNER,
                block.height(), block.hash(), block.parentHash());
        applyOutputs(profile, block);
    }

    private void applyOutputs(AccountChainProfile profile, UtxoBlock block) {
        for (UtxoOutput output : block.outputs()) {
            if (output.blockHeight() != block.height() || !output.blockHash().equalsIgnoreCase(block.hash()))
                throw new IllegalArgumentException("output does not belong to observed block");
            utxos.upsert(profile.getChain(), profile.getNativeSymbol(), output.txHash(), output.vout(),
                    output.address(), output.amount(), output.blockHeight(), output.blockHash(), output.confirmations(), false);
            post(profile, output);
        }
    }

    private void post(AccountChainProfile profile, UtxoOutput output) {
        String chain = profile.getChain();
        var owner = addresses.findEnabledByAddress(chain, profile.getNativeSymbol(), output.address()).orElse(null);
        if (owner == null || owner.getUserId() == null || owner.getUserId() <= 0) return;
        UUID tenant = Objects.requireNonNull(owner.getTenantId(), "deposit tenant missing");
        if (isCollectionAddress(tenant, chain, output.address())
                || deposits.isInternalCollectionTransfer(tenant, chain, output.txHash(), output.address())) return;
        var event = new DepositEvent(ChainType.valueOf(chain), profile.getNativeSymbol(), output.txHash(),
                null, output.address(), output.amount(), output.blockHeight(), output.blockHash(),
                output.confirmations(), null, null);
        deposits.recordAndCredit(event, output.vout(), profile.getDepositConfirmations(), owner.getAccountId());
        if (output.confirmations() >= profile.getDepositConfirmations())
            utxos.markCredited(chain, output.txHash(), output.vout());
        queue.send(WalletQueue.DEPOSIT_EVENT, JacksonJson.writeValue(mapper,
                new UtxoDepositNotification(tenant, chain, profile.getNetwork(), profile.getNativeSymbol(), output)),
                QueueTenant.headers(tenant));
    }

    private boolean isCollectionAddress(UUID tenant, String chain, String address) {
        return gasAccounts.listActiveByTenantAndChain(tenant, chain).stream()
                .map(row -> (UUID) row.get("custody_address_id"))
                .map(id -> custodyAddresses.findFullByTenantAndId(tenant, id))
                .flatMap(java.util.Optional::stream)
                .anyMatch(row -> row.status().equals("ACTIVE") && row.address().equalsIgnoreCase(address));
    }

    public static String scannerName(AccountChainProfile profile) {
        return (profile.getChain() + "-" + profile.getNetwork()).toLowerCase(java.util.Locale.ROOT) + "-block-scanner";
    }
}
