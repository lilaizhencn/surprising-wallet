package com.surprising.wallet.service.chain.aptos;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AptosAddressService {
    private static final String CHAIN = "APTOS";    private final AptosKeyService keyService;    private final ChainJdbcRepository repository;
    public ChainAddressRecord createNativeAddress(long userId, int biz, long derivationIndex, String walletRole) {
        HotWalletRules.requireAllowedReservedAddress(CHAIN, "APT", "APT", userId, biz, derivationIndex, walletRole);
        return repository.findChainAddress(CHAIN, "APT", userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    Ed25519DerivedKey key = keyService.derive(userId, biz, derivationIndex);
                    String address = AptosKeyService.address(key.publicKey());
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .chain(CHAIN)
                            .assetSymbol("APT")
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
                    return repository.findChainAddress(CHAIN, "APT", userId, biz, derivationIndex, walletRole)
                            .orElseThrow();
                });
    }

    public ChainAddressRecord createTokenAddress(String symbol, long userId, int biz,
                                                 long derivationIndex, String walletRole) {
        HotWalletRules.requireAllowedReservedAddress(CHAIN, symbol, "APT", userId, biz, derivationIndex, walletRole);
        ChainAddressRecord owner = createNativeAddress(userId, biz, derivationIndex, walletRole);
        return repository.findChainAddress(CHAIN, symbol, userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .chain(CHAIN)
                            .assetSymbol(symbol)
                            .accountId(owner.getAccountId())
                            .userId(userId)
                            .biz(biz)
                            .addressIndex(derivationIndex)
                            .address(owner.getAddress())
                            .ownerAddress(owner.getAddress())
                            .derivationPath(owner.getDerivationPath())
                            .walletRole(walletRole)
                            .enabled(true)
                            .build();
                    repository.upsertChainAddress(record);
                    return repository.findChainAddress(CHAIN, symbol, userId, biz, derivationIndex, walletRole)
                            .orElseThrow();
                });
    }
}
