package com.surprising.wallet.chain.cardano;

import com.google.protobuf.ByteString;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.service.ChainRpcNodeService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import utxorpc.v1beta.cardano.Cardano;
import utxorpc.v1beta.query.Query;
import utxorpc.v1beta.query.QueryServiceGrpc;
import utxorpc.v1beta.sync.Sync;
import utxorpc.v1beta.sync.SyncServiceGrpc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CardanoRpcClientTest {
    private final ByteString hash = ByteString.copyFrom(HexFormat.of().parseHex("01".repeat(32)));
    private final String owner = MpcCardanoTransactionCodec.address(HexFormat.of().parseHex("02".repeat(32)), false);
    private final AtomicInteger calls = new AtomicInteger();
    private Function<Query.SearchUtxosRequest, Query.SearchUtxosResponse> search;
    private Function<Sync.FollowTipRequest, List<Sync.FollowTipResponse>> follow;
    private Function<Sync.FetchBlockRequest, Sync.FetchBlockResponse> fetch;
    private Server server;
    private CardanoRpcClient rpc;

    @BeforeEach void setup() throws Exception {
        server = ServerBuilder.forPort(0).addService(new QueryServiceGrpc.QueryServiceImplBase() {
            @Override public void searchUtxos(Query.SearchUtxosRequest request, StreamObserver<Query.SearchUtxosResponse> observer) {
                calls.incrementAndGet(); observer.onNext(search.apply(request)); observer.onCompleted();
            }
        }).addService(new SyncServiceGrpc.SyncServiceImplBase() {
            @Override public void fetchBlock(Sync.FetchBlockRequest request, StreamObserver<Sync.FetchBlockResponse> observer) {
                observer.onNext(fetch.apply(request)); observer.onCompleted();
            }
            @Override public void followTip(Sync.FollowTipRequest request, StreamObserver<Sync.FollowTipResponse> observer) {
                follow.apply(request).forEach(observer::onNext); observer.onCompleted();
            }
        }).build().start();
        var profiles = mock(ChainProfileRepository.class);
        var profile = new AccountChainProfile(); profile.setChain("ADA"); profile.setNetwork("testnet");
        when(profiles.findAccountByChain("ADA")).thenReturn(Optional.of(profile));
        var nodes = mock(ChainRpcNodeService.class);
        var node = new ChainRpcNode(); node.setRpcUrl("http://127.0.0.1:" + server.getPort());
        when(nodes.authHeaders(any())).thenReturn(Map.of());
        doAnswer(invocation -> ((Function<ChainRpcNode, ?>) invocation.getArgument(2)).apply(node))
                .when(nodes).withFailover(eq("ADA"), eq("testnet"), any());
        rpc = new CardanoRpcClient(profiles, nodes);
    }
    @AfterEach void stop() throws Exception { server.shutdownNow().awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS); }

    @Test void nativeSearchBindsExactAddressAndReturnsOneSnapshot() {
        search = request -> {
            assertEquals(ByteString.copyFrom(CardanoRpcClient.addressBytes(owner)), request.getPredicate().getMatch().getCardano().getAddress().getExactAddress());
            assertEquals(500, request.getMaxItems());
            return MpcCardanoTransactionCodecTest.snapshot(owner);
        };
        assertEquals(1, rpc.utxos(owner).getItemsCount()); assertEquals(1, calls.get());
    }

    @Test void paginationCannotSilentlyMoveLedgerSnapshotOrRepeatToken() {
        var page = MpcCardanoTransactionCodecTest.snapshot(owner);
        search = request -> page.toBuilder().clearItems().setNextToken("same-token").build();
        assertThrows(IllegalStateException.class, () -> rpc.utxos(owner)); assertEquals(2, calls.get());
        calls.set(0);
        search = request -> request.getStartToken().isEmpty() ? page.toBuilder().setNextToken("next").build()
                : page.toBuilder().clearItems().setLedgerTip(page.getLedgerTip().toBuilder().setHeight(301)).build();
        assertThrows(IllegalStateException.class, () -> rpc.utxos(owner));
    }

    @Test void streamResumesExactSlotHashAndStopsAtBoundedPrefix() {
        var start = point(10);
        follow = request -> {
            assertEquals(start, request.getIntersect(0));
            return List.of(Sync.FollowTipResponse.newBuilder().setReset(start).build(), applied(11), applied(12));
        };
        var blocks = rpc.blocksAfter(start, 20, 1);
        assertEquals(1, blocks.size()); assertEquals(11, blocks.getFirst().getCardano().getHeader().getHeight());
    }

    @Test void streamRejectsIntersectionChangesAndHeightGaps() {
        var start = point(10);
        follow = request -> List.of(Sync.FollowTipResponse.newBuilder().setReset(point(9)).build());
        assertThrows(IllegalStateException.class, () -> rpc.blocksAfter(start, 20, 5));
        follow = request -> List.of(Sync.FollowTipResponse.newBuilder().setReset(start).build(), applied(12));
        assertThrows(IllegalStateException.class, () -> rpc.blocksAfter(start, 20, 5));
        follow = request -> List.of(Sync.FollowTipResponse.newBuilder().setReset(start).build(),
                Sync.FollowTipResponse.newBuilder().setUndo(applied(11).getApply()).build());
        assertThrows(IllegalStateException.class, () -> rpc.blocksAfter(start, 20, 5));
    }

    @Test void rollbackIntersectionMustMatchAnExactStoredSlotAndHash() {
        var latest = CardanoRpcClient.point(point(10)); var previous = CardanoRpcClient.point(point(9));
        follow = request -> {
            assertEquals(List.of(CardanoRpcClient.reference(latest), CardanoRpcClient.reference(previous)), request.getIntersectList());
            return List.of(Sync.FollowTipResponse.newBuilder().setReset(point(9).toBuilder().setHeight(999)).build());
        };
        assertEquals(previous, rpc.commonPoint(List.of(latest, previous)));
        follow = request -> List.of(Sync.FollowTipResponse.newBuilder().setReset(point(8)).build());
        assertThrows(IllegalStateException.class, () -> rpc.commonPoint(List.of(latest, previous)));
        follow = request -> List.of(Sync.FollowTipResponse.newBuilder().setReset(point(9).toBuilder().setSlot(999)).build());
        assertThrows(IllegalStateException.class, () -> rpc.commonPoint(List.of(latest, previous)));
    }

    @Test void nativeIntegersDoNotTruncateAtLongAndAbsentValuesFail() {
        assertEquals(BigInteger.ONE.shiftLeft(64), CardanoRpcClient.integer(Cardano.BigInt.newBuilder()
                .setBigUInt(ByteString.copyFrom(HexFormat.of().parseHex("010000000000000000"))).build()));
        assertEquals(BigInteger.valueOf(-257), CardanoRpcClient.integer(Cardano.BigInt.newBuilder()
                .setBigNInt(ByteString.copyFrom(new byte[] {1, 0})).build()));
        assertThrows(IllegalArgumentException.class, () -> CardanoRpcClient.integer(Cardano.BigInt.getDefaultInstance()));
        assertThrows(IllegalArgumentException.class, () -> rpc.block(Sync.BlockRef.newBuilder().setHeight(1).build()));
    }

    @Test void hashOnlyFetchResolvesTheActualSlotAndHeightAndFullPointStillChecksIdentity() {
        var requestedSlots = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        fetch = request -> {
            assertEquals(hash, request.getRef().getHash()); requestedSlots.add(request.getRef().getSlot());
            var block = applied(11).getApply();
            return Sync.FetchBlockResponse.newBuilder().setBlock(block.toBuilder().setCardano(block.getCardano().toBuilder().setTimestamp(123))).build();
        };
        var fetched = rpc.blockByHash(HexFormat.of().formatHex(hash.toByteArray()));
        assertEquals(22, fetched.getCardano().getHeader().getSlot());
        assertEquals(0, fetched.getCardano().getTimestamp());
        var full = rpc.block(point(11));
        assertEquals(11, full.getCardano().getHeader().getHeight()); assertEquals(123, full.getCardano().getTimestamp());
        assertThrows(IllegalStateException.class, () -> rpc.block(point(10)));
        assertEquals(List.of(0L,22L,20L), requestedSlots);
    }
    private Sync.BlockRef point(long height) { return Sync.BlockRef.newBuilder().setSlot(height * 2).setHash(hash).setHeight(height).build(); }
    private Sync.FollowTipResponse applied(long height) {
        return Sync.FollowTipResponse.newBuilder().setApply(Sync.AnyChainBlock.newBuilder().setNativeBytes(ByteString.copyFrom(new byte[] {1}))
                .setCardano(Cardano.Block.newBuilder().setHeader(Cardano.BlockHeader.newBuilder().setHeight(height).setSlot(height * 2).setHash(hash)))).build();
    }
}
