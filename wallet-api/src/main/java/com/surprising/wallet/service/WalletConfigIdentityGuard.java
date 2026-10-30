package com.surprising.wallet.service;

import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import com.surprising.wallet.repository.DepositRecordRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 地址或资金记录存在后，链及 Token 的派生、归属和金额解释不能再改变。 */
@Service
@RequiredArgsConstructor
public class WalletConfigIdentityGuard {
    private final ChainAddressRepository chainAddressRepository;
    private final LedgerBalanceRepository ledgerBalanceRepository;
    private final DepositRecordRepository depositRecordRepository;
    private final WithdrawalOrderRepository withdrawalOrderRepository;
    private final CollectionRecordRepository collectionRecordRepository;
    private final com.surprising.wallet.repository.CustodyAddressRepository custodyAddresses;

    public void requireUnusedChain(String chain) {
        if (chainAddressRepository.existsByChain(chain)
                || ledgerBalanceRepository.existsByChain(chain)
                || depositRecordRepository.existsByChain(chain)
                || withdrawalOrderRepository.existsByChain(chain)
                || collectionRecordRepository.existsByChain(chain)) {
            throw new IllegalStateException("chain identity, network, runtime currency and BIP44 coin type are locked after addresses or transactions exist");
        }
    }

    public void requireNetwork(String chain, String network) {
        if (custodyAddresses.existsOtherNetwork(chain,network)) {
            throw new IllegalStateException("chain network is pinned by existing custody addresses; a different network cannot take over their funds");
        }
    }

    public void requireUnusedToken(String chain, String symbol) {
        if (chainAddressRepository.existsByChainAndAsset(chain, symbol)
                || ledgerBalanceRepository.existsByChainAndAsset(chain, symbol)
                || depositRecordRepository.existsByChainAndAsset(chain, symbol)
                || withdrawalOrderRepository.existsByChainAndAsset(chain, symbol)
                || collectionRecordRepository.existsByChainAndAsset(chain, symbol)) {
            throw new IllegalStateException("token identity, network, contract and decimals are locked after wallet data exists");
        }
    }
}
