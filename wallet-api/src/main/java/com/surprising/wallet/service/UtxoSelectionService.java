package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.repository.*;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在批处理事务内按租户归属筛选并锁定 UTXO。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Throwable.class)
public class UtxoSelectionService {
    private final UtxoRepository utxos;
    private final ChainAddressRepository addresses;
    private final ChainProfileRepository profiles;

    public int lock(UUID tenantId, String chain, String txHash, int vout, String lockRef) {
        String address = utxos.findAddress(chain, txHash, vout).orElse(null);
        if (address == null || !addresses.existsEnabledAddress(tenantId, chain, address)) {
            return 0;
        }
        return utxos.lock(chain, txHash, vout, lockRef);
    }

    public List<UtxoTransaction> spendable(UUID tenantId, String chain, String assetSymbol,
                                            long requiredConfirmations, int limit, int offset) {
        return utxos.listSpendable(
                chain, assetSymbol, requiredConfirmations, limit, offset,
                runtimeCurrencyId(chain), addresses.listEnabledAddresses(tenantId, chain));
    }

    public List<UtxoTransaction> creditedAtAddress(
            UUID tenantId, String chain, String assetSymbol, String address,
            long requiredConfirmations, int limit) {
        ChainAddressRecord owner = addresses.findEnabledByTenantAndAddress(
                tenantId, chain, assetSymbol, address).orElseThrow(() ->
                new IllegalArgumentException("collection source address does not belong to tenant"));
        if (!"DEPOSIT".equals(owner.getWalletRole())) {
            throw new IllegalArgumentException("collection source must be a deposit address");
        }
        return utxos.listCreditedSpendableAtAddress(chain, assetSymbol, address,
                requiredConfirmations, limit, runtimeCurrencyId(chain));
    }

    private int runtimeCurrencyId(String chain) {
        return profiles.findAccountByChain(chain).orElseThrow(() ->
                new IllegalStateException("missing enabled chain profile for unified UTXO chain " + chain))
                .getRuntimeCurrencyId();
    }
}
