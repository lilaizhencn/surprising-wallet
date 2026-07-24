package com.surprising.wallet.service.chain.xrp;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public
class XrpAddressService {
    private static final String CHAIN = "XRP";    private static final String NATIVE_SYMBOL = "XRP";    private final XrpKeyService keyService;    private final ChainJdbcRepository repository;
    public ChainAddressRecord createNativeAddress(long userId, int biz, long derivationIndex, String walletRole) {
        HotWalletRules.requireAllowedReservedAddress(
                CHAIN, NATIVE_SYMBOL, NATIVE_SYMBOL, userId, biz, derivationIndex, walletRole);
        return repository.findChainAddress(CHAIN, NATIVE_SYMBOL, userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    AccountChainProfile profile = profile();
                    String address = keyService.address(profile, userId, biz, derivationIndex);
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .chain(CHAIN)
                            .assetSymbol(NATIVE_SYMBOL)
                            .accountId(address)
                            .userId(userId)
                            .biz(biz)
                            .addressIndex(derivationIndex)
                            .address(address)
                            .ownerAddress(address)
                            .derivationPath(derivationPath(profile, userId, biz, derivationIndex))
                            .walletRole(walletRole)
                            .enabled(true)
                            .build();
                    repository.upsertChainAddress(record);
                    return repository.findChainAddress(CHAIN, NATIVE_SYMBOL, userId, biz, derivationIndex, walletRole)
                            .orElseThrow();
                });
    }

    public ChainAddressRecord createIssuedCurrencyAddress(String symbol, long userId, int biz,
                                                          long derivationIndex, String walletRole) {
        HotWalletRules.requireAllowedReservedAddress(
                CHAIN, symbol, NATIVE_SYMBOL, userId, biz, derivationIndex, walletRole);
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
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
    private String derivationPath(AccountChainProfile profile, long userId, int biz, long index) {
        return String.format("m/44/%d/%d/%d/%d",
                profile.getBip44CoinType(), biz, userId, index);
    }
}
