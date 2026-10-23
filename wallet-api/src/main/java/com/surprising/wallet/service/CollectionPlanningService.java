package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.HotWalletRules;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.repository.*;
import java.math.BigDecimal;
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
        for (Map<String, Object> row : deposits.listCreditedForCollectionBalance(chain)) {
            BalanceKey key = new BalanceKey((UUID) row.get("tenant_id"),
                    String.valueOf(row.get("asset_symbol")), String.valueOf(row.get("to_address")));
            deposited.merge(key, (BigDecimal) row.get("amount"), BigDecimal::add);
        }
        Map<AddressKey, List<Map.Entry<BalanceKey, BigDecimal>>> depositedByAddress = new HashMap<>();
        for (Map.Entry<BalanceKey, BigDecimal> entry : deposited.entrySet()) {
            BalanceKey key = entry.getKey();
            depositedByAddress.computeIfAbsent(new AddressKey(key.tenantId(), key.address()),
                    ignored -> new ArrayList<>()).add(entry);
        }
        Map<BalanceKey, BigDecimal> collected = new HashMap<>();
        Set<BalanceKey> pending = new HashSet<>();
        for (Map<String, Object> row : collections.listForCollectionBalance(chain)) {
            BalanceKey key = new BalanceKey((UUID) row.get("tenant_id"),
                    String.valueOf(row.get("asset_symbol")), String.valueOf(row.get("from_address")));
            collected.put(key, (BigDecimal) row.get("amount"));
            if (Boolean.TRUE.equals(row.get("pending"))) {
                pending.add(key);
            }
        }
        Map<String, Map<String, Object>> activeAssets = new HashMap<>();
        for (Map<String, Object> row : assets.listActiveByChain(chain)) {
            activeAssets.put(String.valueOf(row.get("symbol")).toLowerCase(Locale.ROOT), row);
        }
        Map<Long, UUID> custodyByChainAddress = new HashMap<>();
        for (Map<String, Object> row : custodyAddresses.listActiveByChain(chain)) {
            custodyByChainAddress.put(((Number) row.get("chain_address_id")).longValue(),
                    (UUID) row.get("id"));
        }
        List<CollectionCandidateRecord> result = new ArrayList<>();
        for (Map<String, Object> address : addresses.listCollectionCandidatesByChain(chain)) {
            UUID tenantId = (UUID) address.get("tenant_id");
            String walletRole = String.valueOf(address.get("wallet_role"));
            long userId = ((Number) address.get("user_id")).longValue();
            if (tenantId == null || !"DEPOSIT".equals(walletRole)
                    || userId == HotWalletRules.DEFAULT_HOT_USER_ID) {
                continue;
            }
            Map<String, Object> nativeAsset = activeAssets.get(
                    String.valueOf(address.get("asset_symbol")).toLowerCase(Locale.ROOT));
            if (nativeAsset == null || !Boolean.TRUE.equals(nativeAsset.get("native_asset"))) {
                continue;
            }
            long chainAddressId = ((Number) address.get("id")).longValue();
            UUID custodyId = custodyByChainAddress.get(chainAddressId);
            if (custodyId == null) {
                continue;
            }
            String sourceAddress = String.valueOf(address.get("address"))
                    .toLowerCase(Locale.ROOT);
            for (Map.Entry<BalanceKey, BigDecimal> entry : depositedByAddress.getOrDefault(
                    new AddressKey(tenantId, sourceAddress), List.of())) {
                BalanceKey key = entry.getKey();
                if (pending.contains(key)) {
                    continue;
                }
                Map<String, Object> asset = activeAssets.get(key.assetSymbol().toLowerCase(Locale.ROOT));
                if (asset == null) {
                    continue;
                }
                BigDecimal amount = entry.getValue().subtract(collected.getOrDefault(key, BigDecimal.ZERO))
                        .max(BigDecimal.ZERO);
                BigDecimal minTransfer = (BigDecimal) asset.get("min_transfer");
                BigDecimal required = (minTransfer == null ? BigDecimal.ZERO : minTransfer)
                        .max(minimumAmount == null ? BigDecimal.ZERO : minimumAmount);
                if (amount.signum() <= 0 || amount.compareTo(required) < 0) {
                    continue;
                }
                result.add(CollectionCandidateRecord.builder().tenantId(tenantId).custodyAddressId(custodyId)
                        .chain(chain).assetSymbol(key.assetSymbol()).accountId((String) address.get("account_id"))
                        .address((String) address.get("address")).ownerAddress((String) address.get("owner_address"))
                        .userId(userId).biz(((Number) address.get("biz")).intValue())
                        .addressIndex(((Number) address.get("address_index")).longValue())
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
                .map(row -> (UUID) row.get("custody_address_id"))
                .map(id -> custodyAddresses.findByTenantAndId(tenantId, id))
                .flatMap(Optional::stream)
                .filter(row -> "ACTIVE".equalsIgnoreCase(String.valueOf(row.get("status"))))
                .map(row -> String.valueOf(row.get("address")))
                .findFirst();
    }
}
