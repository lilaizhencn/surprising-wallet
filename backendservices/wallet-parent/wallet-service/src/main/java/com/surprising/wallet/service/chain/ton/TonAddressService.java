package com.surprising.wallet.service.chain.ton;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.smartcontract.wallet.v4.WalletV4R2;

@Service
@RequiredArgsConstructor
public class TonAddressService {
    private static final String CHAIN = "TON";

    private final TonKeyService keyService;
    private final ChainJdbcRepository repository;

    @Value("${atomex.ton.network:testnet}")
    private String network = "testnet";

    public ChainAddressRecord createNativeAddress(long userId, int biz, long derivationIndex, String walletRole) {
        return repository.findChainAddress(CHAIN, "TON", userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    WalletV4R2 wallet = keyService.wallet(derivationIndex);
                    String address = friendly(wallet.getAddress(), false);
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .chain(CHAIN)
                            .assetSymbol("TON")
                            .accountId(address)
                            .userId(userId)
                            .biz(biz)
                            .addressIndex(derivationIndex)
                            .address(address)
                            .ownerAddress(address)
                            .derivationPath(keyService.derive(derivationIndex).derivationPath())
                            .walletRole(walletRole)
                            .enabled(true)
                            .build();
                    repository.upsertChainAddress(record);
                    return repository.findChainAddress(CHAIN, "TON", userId, biz, derivationIndex, walletRole)
                            .orElseThrow();
                });
    }

    public ChainAddressRecord registerJettonWallet(String symbol, String jettonWalletAddress,
                                                   long userId, int biz, long derivationIndex,
                                                   String walletRole) {
        ChainAddressRecord owner = createNativeAddress(userId, biz, derivationIndex, walletRole);
        return repository.findChainAddress(CHAIN, symbol, userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    String address = friendly(Address.of(jettonWalletAddress), true);
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .chain(CHAIN)
                            .assetSymbol(symbol)
                            .accountId(owner.getAddress())
                            .userId(userId)
                            .biz(biz)
                            .addressIndex(derivationIndex)
                            .address(address)
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

    public String normalizeRaw(String address) {
        return Address.of(address).toRaw();
    }

    private String friendly(Address address, boolean bounceable) {
        boolean testnet = "testnet".equalsIgnoreCase(network);
        return address.toString(true, true, bounceable, testnet);
    }
}
