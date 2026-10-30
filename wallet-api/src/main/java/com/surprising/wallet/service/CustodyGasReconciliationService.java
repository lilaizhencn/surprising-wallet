package com.surprising.wallet.service;

import com.surprising.wallet.model.custody.GasUsageRecord;
import com.surprising.wallet.model.custody.NetworkFee;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Reconcile observed Gas fees; elapsed time alone never proves an operation completed. */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustodyGasReconciliationService {
    private final CustodyGasUsageRepository usages;
    private final CustodyGasAccountingService accounting;
    private final ChainAssetRepository assets;
    private final WithdrawalOrderRepository withdrawalOrders;
    private final EvmTransactionRepository evmTransactions;
    private final SolanaTransactionRepository solanaTransactions;
    private final AptosTransactionRepository aptosTransactions;
    private final SuiTransactionRepository suiTransactions;
    private final TonTransactionRepository tonTransactions;
    private final XrpTransactionRepository xrpTransactions;
    private final MoneroTransactionRepository moneroTransactions;
    private final TronTransactionRepository tronTransactions;

    public void reconcile() {
        UUID cursor = null;
        while (true) {
            var page = usages.listReconciliationCandidates(cursor, 100);
            if (page.isEmpty()) return;
            cursor = page.getLast().id();
            for (GasUsageRecord usage : page) {
                try {
                    if ("OVERDUE".equals(usage.status())) {
                        if (usage.actualAmount() == null || usage.actualAmount().signum() <= 0) {
                            throw new IllegalStateException("overdue gas receipt has no observed fee");
                        }
                        accounting.settle(usage.tenantId(), usage.operationType(), usage.operationId(),
                                usage.actualAmount(), usage.pricingSource(), usage.txHash());
                    } else if ("WITHDRAWAL".equals(usage.operationType())) {
                        reconcileWithdrawal(usage.tenantId(), usage.operationId());
                    }
                    // Unconfirmed batches remain reserved; their owning workflows recover their signed attempts.
                } catch (RuntimeException error) {
                    log.warn("custody gas reconciliation failed: usageId={} error={}", usage.id(), error.getMessage());
                }
            }
        }
    }

    /** The owned order, rather than a caller snapshot or task switch, determines its final outcome. */
    public void reconcileWithdrawal(UUID tenantId, UUID withdrawalId) {
        var usage = usages.find(tenantId, "WITHDRAWAL", withdrawalId).orElse(null);
        if (usage == null || !Set.of("RESERVED", "OVERDUE").contains(usage.status())) return;
        var order = withdrawalOrders.find(usage.chain(), usage.referenceNo(), tenantId)
                .orElseThrow(() -> new IllegalStateException("gas withdrawal order is missing"));
        if ("CONFIRMED".equals(order.getStatus())) {
            var nativeAsset = assets.findDefinition(usage.chain(), usage.nativeSymbol())
                    .orElseThrow(() -> new IllegalStateException("gas native asset is missing"));
            if (!Boolean.TRUE.equals(nativeAsset.getNativeAsset()) || nativeAsset.getDecimals() == null) {
                throw new IllegalStateException("gas native asset precision is invalid");
            }
            var fee = confirmedNetworkFee(order, nativeAsset.getDecimals())
                    .orElse(new NetworkFee(usage.reservedAmount(), "CONFIGURED_RESERVE"));
            accounting.settle(tenantId, "WITHDRAWAL", withdrawalId, fee.amount(), fee.pricingSource(), order.getTxHash());
        } else if (Set.of("FAILED", "REJECTED", "CANCELLED").contains(order.getStatus())) {
            if (order.getTxHash() == null || order.getTxHash().isBlank()) {
                accounting.release(tenantId, "WITHDRAWAL", withdrawalId, "withdrawal ended before broadcast");
            } else {
                // A failed on-chain execution still burns Gas. An absent fee receipt remains reserved for reconciliation.
                var fee = evmTransactions.findConfirmedFee(order.getChain(),order.getTxHash())
                        .orElseThrow(() -> new IllegalStateException("failed withdrawal actual network fee receipt is unavailable"));
                accounting.settle(tenantId,"WITHDRAWAL",withdrawalId,fee,"CHAIN_FAILED_RECEIPT",order.getTxHash());
            }
        }
    }

    private Optional<NetworkFee> confirmedNetworkFee(WithdrawalOrderRecord order, int nativeDecimals) {
        String chain = order.getChain();
        String txHash = order.getTxHash();
        if (txHash == null || txHash.isBlank()) return Optional.empty();
        String normalizedChain = chain == null ? "" : chain.toUpperCase(Locale.ROOT);
        Optional<BigDecimal> amount;
        String source;
        switch (normalizedChain) {
            case "SOLANA" -> {
                amount = solanaTransactions.findConfirmedFeeAtomic(chain, txHash)
                        .map(value -> value.movePointLeft(9));
                source = "CHAIN_CONFIRMED";
            }
            case "APTOS" -> {
                amount = aptosTransactions.findConfirmedFeeAtomic(chain, txHash)
                        .map(value -> value.movePointLeft(8));
                source = "CHAIN_CONFIRMED";
            }
            case "SUI" -> {
                amount = suiTransactions.findConfirmedFeeAtomic(chain, txHash)
                        .map(value -> value.movePointLeft(9));
                source = "CHAIN_CONFIRMED";
            }
            case "TON" -> {
                amount = tonTransactions.findConfirmedFeeAtomic(chain, txHash)
                        .map(value -> value.movePointLeft(9));
                source = "CHAIN_CONFIRMED";
            }
            case "XRP" -> {
                amount = xrpTransactions.findConfirmedFeeAtomic(chain, txHash)
                        .map(value -> value.movePointLeft(6));
                source = "CHAIN_CONFIRMED";
            }
            case "XMR" -> {
                amount = moneroTransactions.findConfirmedFeeAtomic(chain, txHash)
                        .map(value -> value.movePointLeft(nativeDecimals));
                source = "CHAIN_CONFIRMED";
            }
            case "NEAR" -> {
                amount = Optional.empty();
                source = "CONFIGURED_RESERVE";
            }
            case "TRON" -> {
                amount = tronTransactions.findConfirmedFee(chain, txHash);
                source = "CHAIN_CONFIRMED";
            }
            default -> {
                amount = evmTransactions.findConfirmedFee(chain, txHash);
                source = amount.isPresent() ? "CHAIN_RECORDED" : "CONFIGURED_RESERVE";
                if (amount.isEmpty()) {
                    amount = Optional.ofNullable(order.getFee());
                }
            }
        }
        String pricingSource = source;
        return amount.filter(value -> value != null && value.signum() > 0)
                .map(value -> new NetworkFee(value.stripTrailingZeros(), pricingSource));
    }

}
