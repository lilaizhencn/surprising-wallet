package com.surprising.wallet.service;

import com.surprising.wallet.repository.CollectionRecordRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists the exact collection destination while the chosen hot wallet remains locked. */
@Service
public class CollectionAdmissionService {
    private final CollectionRecordRepository records;
    private final CollectionPlanningService legacy;
    private final HotWalletCollectionRoutingService hot;
    private final TransactionTemplate transaction;

    public CollectionAdmissionService(CollectionRecordRepository records, CollectionPlanningService legacy,
            HotWalletCollectionRoutingService hot, PlatformTransactionManager manager) {
        this.records=records;this.legacy=legacy;this.hot=hot;this.transaction=new TransactionTemplate(manager);
    }

    public boolean create(UUID tenantId,UUID sourceCustodyAddressId,String collectionNo,String chain,
                          String asset,String fromAddress,BigDecimal amount,BigDecimal fee,String rawPayload) {
        return Boolean.TRUE.equals(transaction.execute(status->{
            String target=hot.destination(tenantId,chain,asset,amount)
                    .orElseGet(()->legacy.destination(tenantId,chain)
                            .orElseThrow(()->new IllegalStateException("tenant collection destination is unavailable")));
            if(fromAddress.equals(target))return false;
            return records.create(tenantId,sourceCustodyAddressId,collectionNo,chain,asset,
                    fromAddress,target,amount,fee,rawPayload)==1;
        }));
    }
}
