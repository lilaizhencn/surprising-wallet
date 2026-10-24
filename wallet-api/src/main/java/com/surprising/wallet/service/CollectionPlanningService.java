package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.HotWalletRules;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.repository.*;
import java.math.BigDecimal;
import com.surprising.wallet.chain.model.ChainAsset;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 按租户充值、已归集金额和在途状态计算归集候选，解析租户的有效归集地址。 */
@Service
@RequiredArgsConstructor
public class CollectionPlanningService {
    private final DepositRecordRepository deposits;
    private final CollectionRecordRepository collections;
    private final ChainAssetRepository assets;
    private final CustodyAddressRepository custodyAddresses;
    private final ChainAddressRepository addresses;
    private final CustodyGasAccountRepository gasAccounts;

    public List<CollectionCandidateRecord> candidates(
            String chain, BigDecimal minimumAmount, int limit) {
        record BalanceKey(UUID tenantId, String assetSymbol, String address) { }
        record AddressKey(UUID tenantId, String address) { }
        Map<BalanceKey, BigDecimal> deposited = new HashMap<>();
        for (var row : deposits.listCreditedForCollectionBalance(chain)) {
            BalanceKey key = new BalanceKey(row.tenantId(),
                    row.assetSymbol(), row.address());
            deposited.merge(key, row.amount(), BigDecimal::add);
        }
        Map<AddressKey, List<Map.Entry<BalanceKey, BigDecimal>>> depositedByAddress = new HashMap<>();
        for (Map.Entry<BalanceKey, BigDecimal> entry : deposited.entrySet()) {
            BalanceKey key = entry.getKey();
            depositedByAddress.computeIfAbsent(new AddressKey(key.tenantId(), key.address()),
                    ignored -> new ArrayList<>()).add(entry);
        }
        Map<BalanceKey, BigDecimal> collected = new HashMap<>();
        Set<BalanceKey> pending = new HashSet<>();
        for (var row : collections.listForCollectionBalance(chain)) {
            BalanceKey key = new BalanceKey(row.tenantId(),
                    row.assetSymbol(), row.address());
            collected.put(key, row.amount());
            if (row.pending()) {
                pending.add(key);
            }
        }
        Map<String, ChainAsset> activeAssets = new HashMap<>();
        for (ChainAsset row : assets.listActiveByChain(chain)) {
            activeAssets.put(row.getSymbol().toLowerCase(Locale.ROOT), row);
        }
        Map<Long, UUID> custodyByChainAddress = new HashMap<>();
        for (var row : custodyAddresses.listActiveByChain(chain)) {
            custodyByChainAddress.put(row.chainAddressId(),
                    row.id());
        }
        List<CollectionCandidateRecord> result = new ArrayList<>();
        for (var address : addresses.listCollectionCandidatesByChain(chain)) {
            UUID tenantId = address.getTenantId();
            String walletRole = address.getWalletRole();
            long userId = address.getUserId();
            if (tenantId == null || !"DEPOSIT".equals(walletRole)
                    || userId == HotWalletRules.DEFAULT_HOT_USER_ID) {
                continue;
            }
            ChainAsset nativeAsset = activeAssets.get(
                    address.getAssetSymbol().toLowerCase(Locale.ROOT));
            if (nativeAsset == null || !Boolean.TRUE.equals(nativeAsset.getNativeAsset())) {
                continue;
            }
            long chainAddressId = address.getId();
            UUID custodyId = custodyByChainAddress.get(chainAddressId);
            if (custodyId == null) {
                continue;
            }
            String sourceAddress = address.getAddress()
                    .toLowerCase(Locale.ROOT);
            for (Map.Entry<BalanceKey, BigDecimal> entry : depositedByAddress.getOrDefault(
                    new AddressKey(tenantId, sourceAddress), List.of())) {
                BalanceKey key = entry.getKey();
                if (pending.contains(key)) {
                    continue;
                }
                ChainAsset asset = activeAssets.get(key.assetSymbol().toLowerCase(Locale.ROOT));
                if (asset == null) {
                    continue;
                }
                BigDecimal amount = entry.getValue().subtract(collected.getOrDefault(key, BigDecimal.ZERO))
                        .max(BigDecimal.ZERO);
                BigDecimal minTransfer = asset.getMinTransfer();
                BigDecimal required = (minTransfer == null ? BigDecimal.ZERO : minTransfer)
                        .max(minimumAmount == null ? BigDecimal.ZERO : minimumAmount);
                if (amount.signum() <= 0 || amount.compareTo(required) < 0) {
                    continue;
                }
                result.add(CollectionCandidateRecord.builder().tenantId(tenantId).custodyAddressId(custodyId)
                        .chain(chain).assetSymbol(key.assetSymbol()).accountId(address.getAccountId())
                        .address(address.getAddress()).ownerAddress(address.getOwnerAddress())
                        .userId(userId).biz(address.getBiz())
                        .addressIndex(address.getAddressIndex())
                        .walletRole(walletRole).amount(amount).build());
            }
        }
        return result.stream().sorted(Comparator.comparing(CollectionCandidateRecord::getAmount).reversed()
                        .thenComparing(CollectionCandidateRecord::getAddressIndex)).limit(limit).toList();
    }

    /** 查询租户启用的归集目标地址。 */
    public Optional<String> destination(UUID tenantId, String chain) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return gasAccounts.listActiveByTenantAndChain(tenantId, chain).stream()
                .map(row -> row.custodyAddressId())
                .map(id -> custodyAddresses.findFullByTenantAndId(tenantId, id))
                .flatMap(Optional::stream)
                .filter(row -> "ACTIVE".equalsIgnoreCase(String.valueOf(row.status())))
                .map(row -> String.valueOf(row.address()))
                .findFirst();
    }
}
