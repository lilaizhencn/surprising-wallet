package com.surprising.wallet.service.chain.monero;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public
class MoneroAddressService {
    private static final String CHAIN = MoneroWalletRpcClient.CHAIN;    private static final String SYMBOL = MoneroWalletRpcClient.SYMBOL;    private final MoneroWalletRpcClient walletRpcClient;    private final ChainJdbcRepository repository;
    public ChainAddressRecord createNativeAddress(long userId, int biz, long preferredIndex, String walletRole) {
        MoneroWalletRpcClient.Subaddress subaddress = userId == 0 && biz == 0 && preferredIndex == 0
                ? walletRpcClient.primaryAddress()
                : walletRpcClient.createAddress(label(userId, biz, preferredIndex));
        ChainAddressRecord record = ChainAddressRecord.builder()
                .chain(CHAIN)
                .assetSymbol(SYMBOL)
                .accountId(subaddress.address())
                .userId(userId)
                .biz(biz)
                .addressIndex((long) subaddress.addressIndex())
                .address(subaddress.address())
                .ownerAddress(subaddress.address())
                .derivationPath("monero-wallet-rpc:m/0/" + subaddress.addressIndex())
                .walletRole(walletRole)
                .enabled(true)
                .build();
        repository.upsertChainAddress(record);
        return repository.findChainAddress(CHAIN, SYMBOL, userId, biz, subaddress.addressIndex(), walletRole)
                .orElseThrow();
    }
    private static String label(long userId, int biz, long preferredIndex) {
        return "surprising-wallet user=" + userId + " biz=" + biz + " requested-index=" + preferredIndex;
    }
}
