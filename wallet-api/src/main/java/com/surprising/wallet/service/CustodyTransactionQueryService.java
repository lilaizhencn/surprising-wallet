package com.surprising.wallet.service;

import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.PageView;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyDepositRepository;
import com.surprising.wallet.repository.CustodyWithdrawalRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 租户充提历史查询：单表筛选与分页，当前页关联数据批量组合。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CustodyTransactionQueryService {
    private final CustodyDepositRepository deposits;
    private final CustodyWithdrawalRepository withdrawals;
    private final CustodyAddressRepository addresses;
    private final WithdrawalOrderRepository orders;
    private final com.surprising.wallet.repository.CustodyGasUsageRepository gas;

    public List<DepositView> deposits(CustodyPrincipal principal, String chain, String assetSymbol,
                                      String status, String search, int limit, int offset) {
        requireScope(principal, "deposits:read");
        return deposits(principal.tenantId(), chain, assetSymbol, status, search, limit, offset);
    }

    public List<DepositView> depositsForSubject(CustodyPrincipal principal, String chain, String assetSymbol,
                                               String status, String search, String subject, int limit, int offset) {
        requireScope(principal, "deposits:read");
        var base = depositQuery(principal.tenantId(), chain, assetSymbol, status, search);
        if (subject == null || subject.isBlank()) return depositViews(base, limit, offset);
        var query = new CustodyDepositRepository.HistoryQuery(base.tenantId(), base.chain(), base.assetSymbol(),
                base.status(), base.search(), base.matchingAddressIds(), true,
                addresses.findSubjectIds(base.tenantId(), base.chain(), subject));
        return depositViews(query, limit, offset);
    }

    public List<WithdrawalView> withdrawalsForSubject(CustodyPrincipal principal, String chain, String assetSymbol,
                                                     String status, String search, String subject, String externalReference, int limit, int offset) {
        requireScope(principal, "withdrawals:read");
        var base = withdrawalQuery(principal.tenantId(), chain, assetSymbol, status, search);
        var query = new CustodyWithdrawalRepository.HistoryQuery(base.tenantId(),base.chain(),base.assetSymbol(),
                base.status(),base.search(),base.matchingOrderIds(),subject,externalReference==null?"":externalReference.trim());
        return withdrawalViews(query, limit, offset);
    }

    public List<WithdrawalView> withdrawalSubmission(CustodyPrincipal principal, String reference) {
        if (reference==null || reference.isBlank() || reference.length()>160) {
            throw new IllegalArgumentException("original withdrawal reference is required");
        }
        return withdrawalsForSubject(principal,"","","","","",reference,2,0);
    }

    public PageView<DepositView> depositPage(CustodyPrincipal principal, String chain, String assetSymbol,
                                            String status, String search, int limit, int offset) {
        requireScope(principal, "deposits:read");
        var query = depositQuery(principal.tenantId(), chain, assetSymbol, status, search);
        int pageSize = pageSize(limit);
        int pageOffset = Math.max(offset, 0);
        return new PageView<>(depositViews(query, pageSize, pageOffset), deposits.countHistory(query),
                pageSize, pageOffset);
    }

    public List<WithdrawalView> withdrawals(CustodyPrincipal principal, String chain, String assetSymbol,
                                            String status, String search, int limit, int offset) {
        requireScope(principal, "withdrawals:read");
        return withdrawals(principal.tenantId(), chain, assetSymbol, status, search, limit, offset);
    }

    public PageView<WithdrawalView> withdrawalPage(CustodyPrincipal principal, String chain, String assetSymbol,
                                                  String status, String search, int limit, int offset) {
        requireScope(principal, "withdrawals:read");
        var query = withdrawalQuery(principal.tenantId(), chain, assetSymbol, status, search);
        int pageSize = pageSize(limit);
        int pageOffset = Math.max(offset, 0);
        return new PageView<>(withdrawalViews(query, pageSize, pageOffset), withdrawals.countHistory(query),
                pageSize, pageOffset);
    }

    // 平台租户详情已由 CustodyTenantService 校验平台权限及目标租户。
    List<DepositView> deposits(UUID tenantId, String chain, String assetSymbol, String status,
                               String search, int limit, int offset) {
        return depositViews(depositQuery(tenantId, chain, assetSymbol, status, search), limit, offset);
    }

    List<WithdrawalView> withdrawals(UUID tenantId, String chain, String assetSymbol, String status,
                                     String search, int limit, int offset) {
        return withdrawalViews(withdrawalQuery(tenantId, chain, assetSymbol, status, search), limit, offset);
    }

    private CustodyDepositRepository.HistoryQuery depositQuery(
            UUID tenantId, String chain, String assetSymbol, String status, String search) {
        String value = search == null ? "" : search.toLowerCase(Locale.ROOT);
        String normalizedChain = upper(chain);
        List<UUID> matchingAddresses = value.isEmpty() ? List.of()
                : addresses.findHistorySearchIds(tenantId, normalizedChain, value);
        return new CustodyDepositRepository.HistoryQuery(tenantId, normalizedChain, upper(assetSymbol),
                upper(status), value, matchingAddresses, false, List.of());
    }

    private CustodyWithdrawalRepository.HistoryQuery withdrawalQuery(
            UUID tenantId, String chain, String assetSymbol, String status, String search) {
        String value = search == null ? "" : search.toLowerCase(Locale.ROOT);
        String normalizedChain = upper(chain);
        List<Long> matchingOrders = value.isEmpty() ? List.of()
                : orders.findHistorySearchIds(tenantId, normalizedChain, value);
        return new CustodyWithdrawalRepository.HistoryQuery(tenantId, normalizedChain, upper(assetSymbol),
                upper(status), value, matchingOrders, "", "");
    }

    private List<DepositView> depositViews(CustodyDepositRepository.HistoryQuery query, int limit, int offset) {
        var rows = deposits.history(query, limit, offset);
        var addressIds = rows.stream().map(CustodyDepositRepository.HistoryEntry::custodyAddressId)
                .distinct().toList();
        var pageAddresses = addresses.findHistoryAddresses(query.tenantId(), addressIds).stream()
                .collect(Collectors.toMap(CustodyAddressRepository.HistoryAddress::id, Function.identity()));
        return rows.stream().map(row -> {
            var address = pageAddresses.get(row.custodyAddressId());
            return new DepositView(row.id(), row.custodyAddressId(), row.chain(), row.assetSymbol(),
                    row.txHash(), row.logIndex(), row.amount(), row.status(), row.creditedAt(),
                    row.createdAt(), row.updatedAt(), address == null ? null : address.address(),
                    address == null ? null : address.subject());
        }).toList();
    }

    private List<WithdrawalView> withdrawalViews(CustodyWithdrawalRepository.HistoryQuery query,
                                                 int limit, int offset) {
        var rows = withdrawals.history(query, limit, offset);
        var addressIds = rows.stream().map(CustodyWithdrawalRepository.HistoryEntry::custodyAddressId)
                .distinct().toList();
        var pageAddresses = addresses.findHistoryAddresses(query.tenantId(), addressIds).stream()
                .collect(Collectors.toMap(CustodyAddressRepository.HistoryAddress::id, Function.identity()));
        var fees=gas.withdrawalFees(query.tenantId(),rows.stream().map(CustodyWithdrawalRepository.HistoryEntry::id).toList()).stream()
                .collect(Collectors.toMap(com.surprising.wallet.model.custody.GasUsageRecord::operationId,Function.identity()));
        var orderIds = rows.stream().map(CustodyWithdrawalRepository.HistoryEntry::withdrawalOrderId).toList();
        var pageOrders = orders.findHistoryOrders(query.tenantId(), orderIds).stream()
                .collect(Collectors.toMap(WithdrawalOrderRepository.HistoryOrder::id, Function.identity()));
        return rows.stream().map(row -> {
            var order = pageOrders.get(row.withdrawalOrderId());
            var fee=fees.get(row.id());
            var address = pageAddresses.get(row.custodyAddressId());
            return new WithdrawalView(row.id(), row.custodyAddressId(), row.orderNo(), row.externalReference(),
                    row.chain(), row.assetSymbol(), row.toAddress(), row.amount(), row.fee(),
                    order == null ? null : order.txHash(), order == null ? row.status() : order.status(),
                    order == null ? null : order.errorMessage(), row.createdByType(),
                    row.subject(), row.createdAt(), row.updatedAt(),
                    order != null && order.fundsReleased() && java.util.Set.of("FAILED","REJECTED","CANCELLED").contains(order.status()),
                    fee == null || fee.actualAmount()==null ? null : fee.actualAmount().toPlainString(),
                    fee == null ? null : fee.nativeSymbol(), fee == null ? null : fee.pricingSource(),fee == null ? null : fee.status(),row.fundingSource());
        }).toList();
    }

    private static void requireScope(CustodyPrincipal principal, String scope) {
        if (principal == null || principal.tenantId() == null || !principal.hasScope(scope)) {
            throw new CustodyForbiddenException(scope + " scope required");
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static int pageSize(int limit) {
        return Math.min(Math.max(limit, 1), 200);
    }

    public record DepositView(UUID id, UUID custodyAddressId, String chain, String assetSymbol,
                               String txHash, long logIndex, @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal amount, String status,
                               Instant creditedAt, Instant createdAt, Instant updatedAt,
                               String address, String subject) { }

    public record WithdrawalView(UUID id, UUID custodyAddressId, String orderNo, String externalReference,
                                  String chain, String assetSymbol, String toAddress, @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal amount,
                                  @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal fee, String txHash, String status, String errorMessage,
                                  String createdByType, String subject, Instant createdAt, Instant updatedAt, boolean refundable, String networkFee, String networkFeeAsset, String networkFeeSource, String gasStatus,String fundingSource) { }
}
