package com.surprising.wallet.service;

import com.google.protobuf.ByteString;
import com.surprising.wallet.chain.cardano.CardanoExecutionReceipt;
import com.surprising.wallet.chain.cardano.CardanoRpcClient;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.repository.ChainScanBlockRepository;
import com.surprising.wallet.repository.ChainScanHeightRepository;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import utxorpc.v1beta.cardano.Cardano;
import utxorpc.v1beta.query.Query;
import utxorpc.v1beta.sync.Sync;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CardanoScanAnchorServiceTest {
    private final CardanoRpcClient rpc = mock(CardanoRpcClient.class);
    private final ChainScanHeightRepository heights = mock(ChainScanHeightRepository.class);
    private final ChainScanBlockRepository blocks = mock(ChainScanBlockRepository.class);
    private final CardanoScanAnchorService service = new CardanoScanAnchorService(rpc, heights, blocks);
    private final AccountChainProfile profile = new AccountChainProfile();
    private List<Sync.AnyChainBlock> history;
    private String genesis;

    @BeforeEach void setup() throws Exception {
        profile.setChain("ADA"); profile.setNetwork("preprod"); profile.setDepositConfirmations(3); profile.setWithdrawConfirmations(3);
        try (var resource = getClass().getResourceAsStream("/mpc/cardano-anchor-reference.json")) {
            assertNotNull(resource); var reference = new ObjectMapper().readTree(resource); genesis = reference.path("genesisHash").asText();
            var parsed = new ArrayList<Sync.AnyChainBlock>();
            for (var item : reference.path("blocks")) parsed.add(Sync.AnyChainBlock.parseFrom(Base64.getDecoder().decode(item.path("blockBase64").asText())));
            history = List.copyOf(parsed);
        }
        when(rpc.genesis()).thenReturn(Query.ReadGenesisResponse.newBuilder().setGenesis(ByteString.copyFrom(HexFormat.of().parseHex(genesis)))
                .setCardano(Cardano.Genesis.newBuilder().setNetworkId("Testnet").setNetworkMagic(424242)).build());
        when(heights.nativeState("ADA", CardanoSubmissionService.SCANNER, false)).thenReturn(Optional.empty());
        when(rpc.tip()).thenReturn(CardanoRpcClient.reference(history.getFirst().getCardano()));
        when(rpc.blockByHash(anyString())).thenAnswer(call -> history.stream().filter(block -> HexFormat.of().formatHex(
                block.getCardano().getHeader().getHash().toByteArray()).equals(call.getArgument(0))).findFirst().orElseThrow());
        when(rpc.blocksAfter(any(), anyLong(), anyInt())).thenReturn(List.of());
        when(rpc.confirmedBlock(anyInt())).thenCallRealMethod();
    }

    @Test void originalParentHashesResolveConfirmedAncestorWithoutHeightIndexer() {
        var anchor = service.prepare(profile);
        var last = CardanoExecutionReceipt.verifyBlock(history.getLast());
        assertEquals(CardanoRpcClient.point(last.reference()), anchor.point());
        assertEquals(genesis, anchor.genesisHash()); assertEquals(last.parentHash(), anchor.parentHash());
        verify(rpc, times(3)).blockByHash(anyString());
        verify(rpc).blocksAfter(eq(last.reference()), eq(last.reference().getHeight()), eq(1));
        verifyNoInteractions(blocks);
    }

    @Test void existingCursorRetainsOriginalNetworkAndChecksCanonicalIntersection() {
        var saved = CardanoExecutionReceipt.verifyBlock(history.getLast());
        when(heights.nativeState("ADA", CardanoSubmissionService.SCANNER, false)).thenReturn(Optional.of(
                new ChainScanHeightRepository.NativeScanState(genesis, saved.reference().getHeight(), saved.reference().getHeight())));
        when(blocks.nativePoint("ADA", CardanoSubmissionService.SCANNER, saved.reference().getHeight())).thenReturn(Optional.of(
                new ChainScanBlockRepository.NativeBlock(CardanoRpcClient.point(saved.reference()), saved.parentHash())));
        assertEquals(CardanoRpcClient.point(saved.reference()), service.prepare(profile).point());
        verify(rpc, never()).tip(); verify(rpc, never()).blockByHash(anyString());
        verify(rpc).blocksAfter(eq(CardanoRpcClient.reference(CardanoRpcClient.point(saved.reference()))), eq(saved.reference().getHeight()), eq(1));
        when(heights.nativeState("ADA", CardanoSubmissionService.SCANNER, false)).thenReturn(Optional.of(
                new ChainScanHeightRepository.NativeScanState("ff".repeat(32), 100, 100)));
        assertThrows(IllegalStateException.class, () -> service.prepare(profile));
    }

    @Test void anotherNetworkOrNonParentBlockCannotBecomeThePublicationAnchor() {
        profile.setNetwork("mainnet"); assertThrows(IllegalStateException.class, () -> service.prepare(profile));
        profile.setNetwork("preprod");
        String parent = CardanoExecutionReceipt.verifyBlock(history.getFirst()).parentHash();
        when(rpc.blockByHash(parent)).thenReturn(history.getLast());
        assertThrows(IllegalStateException.class, () -> service.prepare(profile));
    }

    @Test void rpcPreparationMustBeOutsideTransactionAndPublicationMustJoinOne() {
        var point = CardanoRpcClient.point(CardanoRpcClient.reference(history.getLast().getCardano()));
        var anchor = new CardanoScanAnchorService.Anchor(profile.getNetwork(), genesis, point, null);
        assertThrows(IllegalStateException.class, () -> service.publish(anchor, profile));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(IllegalStateException.class, () -> service.prepare(profile)); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
}
