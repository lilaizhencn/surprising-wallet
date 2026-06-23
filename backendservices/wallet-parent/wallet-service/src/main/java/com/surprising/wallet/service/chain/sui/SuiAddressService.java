package com.surprising.wallet.service.chain.sui;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SuiAddressService {
    private static final String CHAIN = "SUI";

    private final SuiKeyService keyService;
    private final ChainJdbcRepository repository;

    public ChainAddressRecord createNativeAddress(long userId, int biz, long derivationIndex, String walletRole) {
        return createAddress("SUI", userId, biz, derivationIndex, walletRole);
    }

    public ChainAddressRecord createCoinAddress(String symbol, long userId, int biz,
                                                long derivationIndex, String walletRole) {
        return createAddress(symbol, userId, biz, derivationIndex, walletRole);
    }

    private ChainAddressRecord createAddress(String symbol, long userId, int biz,
                                             long derivationIndex, String walletRole) {
        return repository.findChainAddress(CHAIN, symbol, userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    Ed25519DerivedKey key = keyService.derive(derivationIndex);
                    String address = SuiKeyService.address(key.publicKey());
                    ChainAddressRecord record = ChainAddressRecord.builder()
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
