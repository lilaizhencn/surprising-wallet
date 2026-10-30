package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.CustodyAddressRecord;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyGasAccountRepository;
import com.surprising.wallet.repository.CustodyLedgerEntryRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Tenant principal liquidity; the exchange, rather than a deposit address, owns user entitlements. */
@Service
@RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY,rollbackFor=Throwable.class)
public class CustodyPoolFundingService {
    private final LedgerBalanceRepository balances;
    private final CustodyGasAccountRepository gasAccounts;
    private final CustodyAddressRepository addresses;
    private final ChainAddressRepository chainAddresses;
    private final CustodyLedgerEntryRepository entries;

    public static String accountId(UUID tenantId,String chain,String asset) {
        return "pool:"+tenantId+":"+chain+":"+asset;
    }

    public CustodyAddressRecord source(UUID tenantId,String chain) {
        return gasAccounts.listActiveByTenantAndChain(tenantId,chain).stream()
                .map(account->addresses.require(tenantId,account.custodyAddressId()))
                .filter(address->"ACTIVE".equals(address.status()))
                .filter(address->chainAddresses.findOwned(tenantId,address.chainAddressId())
                        .map(owner->Boolean.TRUE.equals(owner.getEnabled())).orElse(false))
                .findFirst().orElseThrow(()->new IllegalStateException("tenant withdrawal pool address is unavailable"));
    }

    public String fund(UUID tenantId,CustodyAddressRecord pool,String asset,BigDecimal amount,String reference) {
        if(!tenantId.equals(pool.tenantId()) || amount==null || amount.signum()<=0) {
            throw new IllegalArgumentException("owned pool and positive principal are required");
        }
        String chain=pool.chain(),account=accountId(tenantId,chain,asset);
        BigDecimal needed=amount.subtract(balances.ensureAndLockOwned(tenantId,chain,asset,account));
        if(needed.signum()<=0)return account;
        var excluded=new ArrayList<String>();excluded.add(account);
        // Inactive Gas accounts remain protected. Their native balances are never customer principal.
        for(var gas:gasAccounts.listByTenantAndChain(tenantId,chain)) {
            var address=addresses.require(tenantId,gas.custodyAddressId());
            var owner=chainAddresses.findOwned(tenantId,address.chainAddressId()).orElseThrow();
            excluded.add(owner.getAccountId());
        }
        var contributions=balances.contributeToPool(tenantId,chain,asset,needed,excluded);
        BigDecimal total=contributions.stream().map(LedgerBalanceRepository.PoolContribution::amount)
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        if(total.compareTo(needed)!=0)throw new IllegalArgumentException("tenant principal pool has insufficient available liquidity");
        if (balances.increment(tenantId,chain,asset,account,total) != 1) {
            throw new IllegalStateException("pool funding credit lost its owned ledger account");
        }
        for(var contribution:contributions) {
            if(entries.insertIfAbsent(UUID.randomUUID(),tenantId,null,chain,asset,contribution.accountId(),
                    "POOL_FUNDING_DEBIT","DEBIT",contribution.amount(),"WITHDRAWAL_FUNDING",
                    reference+":"+contribution.balanceId())!=1) {
                throw new IllegalStateException("pool funding source audit already exists");
            }
        }
        if(entries.insertIfAbsent(UUID.randomUUID(),tenantId,pool.id(),chain,asset,account,
                "POOL_FUNDING_CREDIT","CREDIT",total,"WITHDRAWAL_FUNDING",reference)!=1) {
            throw new IllegalStateException("pool funding credit audit already exists");
        }
        return account;
    }
}
