package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.model.ReorgedDeposit;
import com.surprising.wallet.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Owns the atomic transition between canonical deposits, ledger balances and custody projections. */
@Service
@RequiredArgsConstructor
public class DepositService {
    private final ChainAddressRepository addresses;
    private final DepositRecordRepository deposits;
    private final LedgerBalanceRepository balances;
    private final ChainScanBlockRepository blocks;
    private final UtxoRepository utxos;
    private final CollectionRecordRepository collections;
    private final ChainSigningTransactionRepository signingTransactions;
    private final CustodyDepositProjectionService projection;

    @Transactional(rollbackFor = Throwable.class)
    public boolean recordAndCredit(DepositEvent event, int requiredConfirmations) {
        return recordAndCredit(event, 0L, requiredConfirmations);
    }

    @Transactional(rollbackFor = Throwable.class)
    public boolean recordAndCredit(DepositEvent event, long logIndex, int requiredConfirmations) {
        return recordAndCredit(event, logIndex, requiredConfirmations, event.toAddress().toLowerCase(Locale.ROOT));
    }

    @Transactional(rollbackFor = Throwable.class)
    public boolean recordAndCredit(DepositEvent event, long logIndex, int requiredConfirmations, String accountId) {
        String chain = event.chainType().name();
        UUID tenantId = requireTenant(chain, accountId, event.toAddress());
        if (isInternalCollectionTransfer(tenantId, chain, event.txId(), event.toAddress())) return false;
        String status = "CONFIRMED";
        if (event.confirmations() <= 0) status = "DETECTED";
        else if (event.confirmations() < requiredConfirmations) status = "CONFIRMING";
        int recorded = deposits.upsert(tenantId, chain, event.assetSymbol(), event.txId(), logIndex,
                event.fromAddress(), event.toAddress(), event.tokenAddress(), event.amount(), event.blockHeight(),
                event.blockHash(), event.confirmations(), status, accountId, event.rawPayload());
        if (recorded != 1) throw new IllegalStateException("deposit record belongs to another tenant");
        if (event.confirmations() < requiredConfirmations) return false;
        var credit = deposits.markCredited(tenantId, chain, event.txId(), logIndex);
        if (credit.isEmpty()) return false;
        balances.increment(tenantId, chain, event.assetSymbol(), accountId, event.amount());
        projection.credit(tenantId, event, logIndex, accountId, credit.orElseThrow());
        return true;
    }

    @Transactional(rollbackFor = Throwable.class)
    public BlockObservation observeCanonicalBlock(String chain, String scannerName, long blockHeight,
                                                   String blockHash, String parentHash) {
        String normalizedChain = requireText(chain, "chain").toUpperCase(Locale.ROOT);
        String scanner = requireText(scannerName, "scannerName");
        String hash = requireText(blockHash, "blockHash");
        List<String> existing = blocks.findHashForUpdate(normalizedChain, scanner, blockHeight);
        if (existing.isEmpty()) {
            blocks.insert(normalizedChain, scanner, blockHeight, hash, parentHash);
            return new BlockObservation(false, null, hash, 0);
        }
        String previous = existing.getFirst();
        if (previous.equalsIgnoreCase(hash)) {
            blocks.updateObservation(normalizedChain, scanner, blockHeight, previous, parentHash);
            return new BlockObservation(false, previous, hash, 0);
        }
        var orphaned = deposits.listForReorg(normalizedChain, blockHeight, hash);
        utxos.markOrphaned(normalizedChain, blockHeight, hash);
        for (var deposit : orphaned) {
            reverse(deposit, hash, "canonical block changed from " + previous + " to " + hash);
        }
        blocks.updateObservation(normalizedChain, scanner, blockHeight, hash, parentHash);
        return new BlockObservation(true, previous, hash, orphaned.size());
    }

    private void reverse(DepositRecordRepository.ReorgRecord deposit, String replacementHash, String reason) {
        BigDecimal reversed = BigDecimal.ZERO;
        BigDecimal deficit = BigDecimal.ZERO;
        if (deposit.credited()) {
            BigDecimal available = balances.findAvailableForUpdate(
                    deposit.tenantId(), deposit.chain(), deposit.assetSymbol(), deposit.accountId());
            reversed = available.min(deposit.amount()).max(BigDecimal.ZERO);
            deficit = deposit.amount().subtract(reversed);
            if (reversed.signum() > 0 && !balances.debit(deposit.chain(), deposit.assetSymbol(),
                    deposit.accountId(), reversed, deposit.tenantId())) {
                throw new IllegalStateException("unable to reverse orphaned deposit balance");
            }
        }
        if (deposits.markReorged(deposit.id(), reason) != 1) {
            throw new IllegalStateException("unable to mark deposit reorged: " + deposit.id());
        }
        if (deposit.credited()) {
            projection.reorg(new ReorgedDeposit(deposit.id(), deposit.tenantId(), deposit.chain(),
                    deposit.assetSymbol(), deposit.txHash(), deposit.logIndex(), deposit.accountId(),
                    deposit.toAddress(), deposit.amount(), reversed, deficit, deposit.creditGeneration(),
                    deposit.blockHeight(), deposit.blockHash(), replacementHash, reason));
        }
    }

    public boolean isInternalCollectionTransfer(UUID tenantId, String chain, String txHash, String toAddress) {
        if (collections.existsInternalTransfer(tenantId, chain, txHash, toAddress)) return true;
        // Replaced transaction hashes remain internal after a collection address rotates.
        return signingTransactions.findCollectionBusinessNo(chain, tenantId, txHash)
                .filter(no -> collections.matchesInternalDestination(tenantId, chain, no, toAddress)).isPresent();
    }

    private UUID requireTenant(String chain, String accountId, String address) {
        List<UUID> owners = addresses.listTenantIds(chain, accountId, address);
        if (owners.isEmpty()) throw new IllegalStateException("deposit address is not assigned to a tenant");
        if (owners.size() > 1) throw new IllegalStateException("deposit address maps to more than one tenant");
        return owners.getFirst();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    public record BlockObservation(boolean reorg, String previousBlockHash, String blockHash,
                                   int reversedDepositCount) { }
}
