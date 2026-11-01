package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.ChainScanPoint;
import com.surprising.wallet.chain.monero.MoneroDaemonClient;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.repository.ChainScanBlockRepository;
import com.surprising.wallet.repository.ChainScanHeightRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Native Monero's public network/confirmed anchor is prepared before its multisig ceremony. */
@Service
@RequiredArgsConstructor
public class MoneroScanAnchorService {
    public static final String SCANNER="XMR_NATIVE";
    private final MoneroDaemonClient daemon;
    private final ChainScanHeightRepository heights;
    private final ChainScanBlockRepository blocks;
    public record Anchor(String network,String genesisHash,ChainScanPoint point,String parentHash) {
        public Anchor {
            require(java.util.Set.of("mainnet","testnet","stagenet","regtest").contains(network)
                    && genesisHash!=null && genesisHash.matches("[0-9a-f]{64}") && point!=null
                    && point.height()==point.slot() && parentHash!=null && parentHash.matches("[0-9a-f]{64}"),"invalid Monero public scan anchor");
        }
    }
    public Anchor prepare(AccountChainProfile profile) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"Monero anchor RPC must run outside the address transaction");
        require("XMR".equals(profile.getChain()),"Monero scan anchor requested for another chain");
        var network=daemon.network(profile.getNetwork());
        var existing=heights.nativeState("XMR",SCANNER,false);
        if(existing.isPresent()) {
            require(existing.get().genesisHash().equals(network.genesisHash()),"Monero daemon genesis changed after address publication");
            var saved=blocks.nativePoint("XMR",SCANNER,existing.get().safeHeight()).orElseThrow();
            require(daemon.header(network.name(),saved.point().height()).hash().equals(saved.point().hash()),"Monero scan anchor needs canonical reconciliation before new addresses");
            return new Anchor(network.name(),network.genesisHash(),saved.point(),saved.parentHash());
        }
        int depth=Math.max(1,Math.max(profile.getDepositConfirmations(),profile.getWithdrawConfirmations()));
        require(depth<=10000,"Monero anchor confirmation depth exceeds bound");
        var header=daemon.header(network.name(),Math.max(0,network.height()-depth));
        return new Anchor(network.name(),network.genesisHash(),new ChainScanPoint(header.height(),header.height(),header.hash()),header.parentHash());
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Throwable.class)
    public void publish(Anchor anchor,AccountChainProfile profile) {
        require(TransactionSynchronizationManager.isActualTransactionActive(),"Monero anchor publication requires the address transaction");
        require(anchor!=null && "XMR".equals(profile.getChain()) && anchor.network().equals(profile.getNetwork()),"Monero address network changed after anchor preparation");
        int inserted=heights.initializeNative("XMR",SCANNER,anchor.genesisHash(),anchor.point().height());
        var state=heights.nativeState("XMR",SCANNER,true).orElseThrow();
        require(state.genesisHash().equals(anchor.genesisHash()),"Monero address anchor belongs to another network");
        if(inserted==1)blocks.insertNative("XMR",SCANNER,anchor.point(),anchor.parentHash());
        else require(blocks.nativePoint("XMR",SCANNER,state.safeHeight()).isPresent(),"Monero scan cursor has no canonical header");
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
