package com.surprising.wallet.service.chain.sui;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SuiAddressService {
    private static final String CHAIN = "SUI";

    private final SuiKeyService keyService;
    private final ChainJdbcRepository repository;

    public ChainAddressRecord createNativeAddress(UUID tenantId, long userId, int biz,
                                                  long derivationIndex, String walletRole) {
        return createAddress(tenantId, "SUI", userId, biz, derivationIndex, walletRole);
    }

    public ChainAddressRecord createCoinAddress(UUID tenantId, String symbol, long userId, int biz,
                                                long derivationIndex, String walletRole) {
        return createAddress(tenantId, symbol, userId, biz, derivationIndex, walletRole);
    }

    private ChainAddressRecord createAddress(UUID tenantId, String symbol, long userId, int biz,
                                             long derivationIndex, String walletRole) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        HotWalletRules.requireAllowedReservedAddress(CHAIN, symbol, "SUI", userId, biz, derivationIndex, walletRole);
        return repository.findChainAddress(CHAIN, symbol, userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    Ed25519DerivedKey key = keyService.derive(userId, biz, derivationIndex);
                    String address = SuiKeyService.address(key.publicKey());
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .tenantId(tenantId)
                            .chain(CHAIN)
                            .assetSymbol(symbol)
                            .accountId(address)
                            .userId(userId)
                            .biz(biz)
                            .addressIndex(derivationIndex)
                            .address(address)
                            .ownerAddress(address)
                            .derivationPath(key.derivationPath())
                            .walletRole(walletRole)
                            .enabled(true)
                            .build();
                    repository.upsertChainAddress(record);
                    return repository.findChainAddress(CHAIN, symbol, userId, biz, derivationIndex, walletRole)
                            .orElseThrow();
                });
    }
}
