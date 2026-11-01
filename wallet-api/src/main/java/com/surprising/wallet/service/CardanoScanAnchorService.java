package com.surprising.wallet.service;

import com.surprising.wallet.chain.cardano.CardanoExecutionReceipt;
import com.surprising.wallet.chain.cardano.CardanoRpcClient;
import com.surprising.wallet.common.chain.ChainScanPoint;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.repository.ChainScanBlockRepository;
import com.surprising.wallet.repository.ChainScanHeightRepository;
import java.util.HexFormat;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Prepares a confirmed native scan anchor before DKG; publication joins the address transaction without RPC. */
@Service
@RequiredArgsConstructor
public class CardanoScanAnchorService {
    private final CardanoRpcClient rpc;
    private final ChainScanHeightRepository heights;
    private final ChainScanBlockRepository blocks;

    public record Anchor(String network, String genesisHash, ChainScanPoint point, String parentHash) {
        public Anchor {
            if (network == null || network.isBlank() || genesisHash == null || !genesisHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Cardano anchor network identity is absent");
            Objects.requireNonNull(point);
            if (parentHash != null && !parentHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid Cardano anchor parent");
        }
    }

    public Anchor prepare(AccountChainProfile profile) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "Cardano anchor RPC must run outside the address transaction");
        require("ADA".equals(profile.getChain()), "Cardano anchor requested for another chain");
        var genesis = rpc.genesis();
        require(genesis.hasCardano() && genesis.getGenesis().size() == 32, "Cardano native genesis is absent");
        boolean mainnet = "mainnet".equalsIgnoreCase(profile.getNetwork()) || "main".equalsIgnoreCase(profile.getNetwork());
        require((mainnet && "Mainnet".equalsIgnoreCase(genesis.getCardano().getNetworkId()))
                || (!mainnet && "Testnet".equalsIgnoreCase(genesis.getCardano().getNetworkId())), "Cardano profile and native network differ");
        String hash = HexFormat.of().formatHex(genesis.getGenesis().toByteArray());
        var existing = heights.nativeState("ADA", CardanoSubmissionService.SCANNER, false);
        if (existing.isPresent()) {
            require(existing.get().genesisHash().equals(hash), "Cardano native node changed after addresses were published");
            var saved = blocks.nativePoint("ADA", CardanoSubmissionService.SCANNER, existing.get().safeHeight()).orElseThrow();
            rpc.blocksAfter(CardanoRpcClient.reference(saved.point()), saved.point().height(), 1);
            return new Anchor(profile.getNetwork(), hash, saved.point(), saved.parentHash());
        }
        int depth = Math.max(1, Math.max(profile.getDepositConfirmations(), profile.getWithdrawConfirmations()));
        require(depth <= 10_000, "Cardano anchor confirmation depth exceeds bound");
        var current = rpc.confirmedBlock(depth);
        var point = CardanoRpcClient.point(current.reference());
        return new Anchor(profile.getNetwork(), hash, point, current.parentHash());
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Throwable.class)
    public void publish(Anchor anchor, AccountChainProfile profile) {
        require(TransactionSynchronizationManager.isActualTransactionActive(), "Cardano anchor publication requires the address transaction");
        require(anchor != null && "ADA".equals(profile.getChain()) && anchor.network().equals(profile.getNetwork()),
                "Cardano address network changed after anchor preparation");
        int inserted = heights.initializeNative("ADA", CardanoSubmissionService.SCANNER, anchor.genesisHash(), anchor.point().height());
        var state = heights.nativeState("ADA", CardanoSubmissionService.SCANNER, true).orElseThrow();
        require(state.genesisHash().equals(anchor.genesisHash()), "Cardano address anchor belongs to another native network");
        if (inserted == 1) blocks.insertNative("ADA", CardanoSubmissionService.SCANNER, anchor.point(), anchor.parentHash());
        else require(blocks.nativePoint("ADA", CardanoSubmissionService.SCANNER, state.safeHeight()).isPresent(), "Cardano native cursor has no stored block reference");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
