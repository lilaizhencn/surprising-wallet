package com.surprising.wallet.service;

import com.surprising.wallet.model.custody.GasOperationReference;
import com.surprising.wallet.model.custody.GasUsageRecord;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyGasAccountRepository;
import com.surprising.wallet.repository.CustodyGasUsageRepository;
import com.surprising.wallet.repository.CustodyLedgerEntryRepository;
import com.surprising.wallet.repository.CustodyWithdrawalRepository;
import com.surprising.wallet.repository.EvmCollectionBatchRepository;
import com.surprising.wallet.repository.EvmWithdrawalBatchRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Gas reservation, release and settlement own their money and receipt transaction. */
@Service
public class CustodyGasAccountingService {
    private final CustodyGasUsageRepository usages;
    private final CustodyGasAccountRepository accounts;
    private final CustodyAddressRepository addresses;
    private final ChainAddressRepository chainAddresses;
    private final LedgerBalanceRepository balances;
    private final CustodyLedgerEntryRepository entries;
    private final CustodyWithdrawalRepository withdrawals;
    private final EvmCollectionBatchRepository collections;
    private final EvmWithdrawalBatchRepository payouts;
    private final TransactionTemplate transactions;

    public CustodyGasAccountingService(CustodyGasUsageRepository usages, CustodyGasAccountRepository accounts,
            CustodyAddressRepository addresses, ChainAddressRepository chainAddresses,
            LedgerBalanceRepository balances, CustodyLedgerEntryRepository entries,
            CustodyWithdrawalRepository withdrawals, EvmCollectionBatchRepository collections,
            EvmWithdrawalBatchRepository payouts, PlatformTransactionManager manager) {
        this.usages = usages;
        this.accounts = accounts;
        this.addresses = addresses;
        this.chainAddresses = chainAddresses;
        this.balances = balances;
        this.entries = entries;
        this.withdrawals = withdrawals;
        this.collections = collections;
        this.payouts = payouts;
        this.transactions = new TransactionTemplate(manager);
    }

    /** Lock the owned operation before checking its receipt; concurrent retries freeze once. */
    public GasUsageRecord reserve(UUID tenantId, String operationType, UUID operationId, BigDecimal amount) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        requireAmount(amount);
        String type = operationType == null ? "" : operationType.trim().toUpperCase(Locale.ROOT);
        return transactions.execute(status -> {
            GasOperationReference operation = (switch (type) {
                case "WITHDRAWAL" -> withdrawals.lockGasOperation(tenantId, operationId);
                case "COLLECTION_BATCH" -> collections.lockGasOperation(tenantId, operationId);
                case "WITHDRAWAL_BATCH" -> payouts.lockGasOperation(tenantId, operationId);
                default -> throw new IllegalArgumentException("unsupported gas operation type");
            }).orElseThrow(() -> new IllegalArgumentException("gas operation does not belong to tenant"));
            var existing = usages.find(tenantId, type, operationId).orElse(null);
            if (existing != null) {
                if (!"RESERVED".equals(existing.status()) || existing.reservedAmount().compareTo(amount) != 0) {
                    throw new IllegalStateException("gas reservation already exists with a different amount or terminal state");
                }
                return existing;
            }
            var account = accounts.lockForChain(tenantId, operation.chain())
                    .filter(row -> "ACTIVE".equals(row.status()))
                    .orElseThrow(() -> new IllegalStateException("active gas account is missing"));
            if (usages.existsOverdue(tenantId, account.id())) throw new IllegalStateException("gas account has an overdue reservation");
            String ledgerAccount = ledgerAccount(tenantId, account);
            if (!balances.freeze(operation.chain(), account.nativeSymbol(), ledgerAccount, amount, tenantId)) {
                throw new IllegalStateException("insufficient gas balance");
            }
            usages.insert(UUID.randomUUID(), tenantId, account.id(), type, operationId,
                    operation.referenceNo(), operation.chain(), account.nativeSymbol(), amount, "CONFIGURED_RESERVE");
            return usages.find(tenantId, type, operationId).orElseThrow();
        });
    }

    public GasUsageRecord release(UUID tenantId, String operationType, UUID operationId, String reason) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        return transactions.execute(status -> {
            GasUsageRecord usage = usages.findForUpdate(tenantId, operationType, operationId).orElseThrow();
            if (!"RESERVED".equals(usage.status())) return usage;
            var account = accounts.lockById(tenantId, usage.gasAccountId()).orElseThrow();
            if (!balances.release(usage.chain(), usage.nativeSymbol(), ledgerAccount(tenantId, account), usage.reservedAmount(), tenantId)) {
                throw new IllegalStateException("gas balance is inconsistent");
            }
            if (usages.release(tenantId, operationType, operationId, reason) != 1) throw new IllegalStateException("gas reservation changed during release");
            return usages.find(tenantId, operationType, operationId).orElseThrow();
        });
    }

    /** An observed fee can exceed the reservation; retain its lock and receipt until funded. */
    public GasUsageRecord settle(UUID tenantId, String operationType, UUID operationId,
                                BigDecimal actualAmount, String pricingSource, String txHash) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        return transactions.execute(status -> {
            GasUsageRecord usage = usages.findForUpdate(tenantId, operationType, operationId).orElseThrow();
            if (!Set.of("RESERVED", "OVERDUE").contains(usage.status())) return usage;
            BigDecimal actual = actualAmount == null || actualAmount.signum() <= 0
                    ? usage.reservedAmount() : actualAmount.stripTrailingZeros();
            requireAmount(actual);
            var account = accounts.lockById(tenantId, usage.gasAccountId()).orElseThrow();
            String ledgerAccount = ledgerAccount(tenantId, account);
            if (!balances.settleReserved(usage.chain(), usage.nativeSymbol(), ledgerAccount,
                    usage.reservedAmount(), actual, tenantId)) {
                if (usages.markOverdue(tenantId, usage.id(), actual, pricingSource, txHash,
                        "actual network fee exceeded funded gas balance") != 1) throw new IllegalStateException("gas reservation changed during overdue transition");
                return usages.find(tenantId, operationType, operationId).orElseThrow();
            }
            if (usages.settleReservedOrOverdue(tenantId, usage.id(), actual, pricingSource, txHash) != 1) {
                throw new IllegalStateException("gas reservation changed during settlement");
            }
            entries.insertIfAbsent(UUID.randomUUID(), tenantId, account.custodyAddressId(), usage.chain(),
                    usage.nativeSymbol(), ledgerAccount, "NETWORK_FEE", "DEBIT", actual,
                    usage.operationType(), usage.referenceNo());
            return usages.find(tenantId, operationType, operationId).orElseThrow();
        });
    }

    private String ledgerAccount(UUID tenantId, CustodyGasAccountRepository.AccountingAccount account) {
        var address = addresses.require(tenantId, account.custodyAddressId());
        var chainAddress = chainAddresses.findOwned(tenantId, address.chainAddressId())
                .orElseThrow(() -> new IllegalStateException("gas chain address is missing"));
        if (!account.chain().equals(address.chain()) || !account.chain().equals(chainAddress.getChain())) {
            throw new IllegalStateException("gas account and address chain mismatch");
        }
        return chainAddress.getAccountId();
    }

    private static void requireAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("gas amount must be positive");
        BigDecimal exact = amount.stripTrailingZeros();
        // Balances have scale 18; usage and entry receipts allow at most 54 integer digits.
        if (exact.scale() > 18 || exact.precision() - exact.scale() > 54) {
            throw new IllegalArgumentException("gas amount exceeds ledger precision");
        }
    }
}
