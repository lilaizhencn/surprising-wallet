package com.surprising.wallet.chain.cardano;

import com.google.protobuf.ByteString;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.service.ChainRpcNodeService;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import org.bitcoinj.base.Bech32;
import org.springframework.stereotype.Component;
import utxorpc.v1beta.cardano.Cardano;
import utxorpc.v1beta.query.Query;
import utxorpc.v1beta.query.QueryServiceGrpc;
import utxorpc.v1beta.submit.Submit;
import utxorpc.v1beta.submit.SubmitServiceGrpc;
import utxorpc.v1beta.sync.Sync;
import utxorpc.v1beta.sync.SyncServiceGrpc;

import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Public native node RPC. No wallet keys, indexer or auxiliary database. */
@Component
public class CardanoRpcClient {
    public static final String CHAIN = "ADA";
    private static final int MAX_UTXOS = 10_000;
    private final ChainProfileRepository profiles;
    private final ChainRpcNodeService nodes;

    public CardanoRpcClient(ChainProfileRepository profiles, ChainRpcNodeService nodes) {
        this.profiles = profiles;
        this.nodes = nodes;
    }

    public Query.ReadGenesisResponse genesis() {
        return call(channel -> query(channel).readGenesis(Query.ReadGenesisRequest.getDefaultInstance()));
    }

    public Query.ReadParamsResponse parameters() {
        return call(channel -> query(channel).readParams(Query.ReadParamsRequest.getDefaultInstance()));
    }

    public Sync.BlockRef tip() {
        return call(channel -> sync(channel).readTip(Sync.ReadTipRequest.getDefaultInstance()).getTip());
    }

    public CardanoExecutionReceipt.Block confirmedBlock(int confirmations) {
        if (confirmations < 1 || confirmations > 10_000) throw new IllegalArgumentException("invalid Cardano confirmation depth");
        var head = tip();
        var current = CardanoExecutionReceipt.verifyBlock(blockByHash(HexFormat.of().formatHex(head.getHash().toByteArray())));
        if (!point(head).equals(point(current.reference()))) throw new IllegalStateException("Cardano confirmed anchor tip differs");
        for (int depth = 1; depth < confirmations && current.reference().getHeight() > 0; depth++) {
            if (current.parentHash() == null) throw new IllegalStateException("Cardano parent chain ended before origin");
            var parent = CardanoExecutionReceipt.verifyBlock(blockByHash(current.parentHash()));
            if (parent.reference().getHeight() != current.reference().getHeight() - 1
                    || parent.reference().getSlot() >= current.reference().getSlot()
                    || !HexFormat.of().formatHex(parent.reference().getHash().toByteArray()).equals(current.parentHash()))
                throw new IllegalStateException("Cardano parent hash, height or slot differs");
            current = parent;
        }
        blocksAfter(current.reference(), current.reference().getHeight(), 1);
        return current;
    }

    public Sync.AnyChainBlock block(Sync.BlockRef ref) {
        requireRef(ref);
        var result = fetchBlock(ref);
        var header = result.getCardano().getHeader();
        if (header.getSlot() != ref.getSlot() || header.getHeight() != ref.getHeight())
            throw new IllegalStateException("Cardano node returned a different block point");
        return result;
    }

    /** Native 11.1.3 accepts a hash alone; the returned header resolves its slot and height. */
    public Sync.AnyChainBlock blockByHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Cardano block hash must be 32 bytes");
        var expected = ByteString.copyFrom(HexFormat.of().parseHex(hash));
        var result = fetchBlock(Sync.BlockRef.newBuilder().setHash(expected).build());
        // The native server derives auxiliary timestamp from the requested slot (zero in a hash-only request).
        // Preserve the original bytes and verified header identity; do not expose genesis time as block time.
        return result.toBuilder().setCardano(result.getCardano().toBuilder().clearTimestamp()).build();
    }

    private Sync.AnyChainBlock fetchBlock(Sync.BlockRef reference) {
        return call(channel -> {
            var result = sync(channel).fetchBlock(Sync.FetchBlockRequest.newBuilder().setRef(reference).build());
            if (!result.hasBlock() || !result.getBlock().hasCardano())
                throw new IllegalStateException("Cardano native block is absent");
            var header = result.getBlock().getCardano().getHeader();
            if (!header.getHash().equals(reference.getHash()))
                throw new IllegalStateException("Cardano node returned a different block");
            return result.getBlock();
        });
    }

    /** A bounded prefix after the exact persisted intersection, excluding blocks above throughHeight. */
    public List<Sync.AnyChainBlock> blocksAfter(Sync.BlockRef intersection, long throughHeight, int limit) {
        if (throughHeight < 0 || limit < 1 || limit > 500)
            throw new IllegalArgumentException("invalid Cardano block scan bound");
        if (!intersection.equals(Sync.BlockRef.getDefaultInstance())) requireRef(intersection);
        return call(channel -> {
            List<Sync.AnyChainBlock> blocks = new ArrayList<>();
            var context = Context.current().withCancellation();
            try {
                return context.call(() -> {
                    var responses = sync(channel).followTip(Sync.FollowTipRequest.newBuilder()
                            .addIntersect(intersection).build());
                    boolean reset = false;
                    long previous = intersection.getHeight();
                    while (responses.hasNext()) {
                        var response = responses.next();
                        if (response.hasReset()) {
                            if (reset || !samePoint(intersection, response.getReset()))
                                throw new IllegalStateException("Cardano scan intersection changed; reconcile reorg before advancing");
                            reset = true;
                            if (!intersection.getHash().isEmpty() && previous >= throughHeight) break;
                        } else if (response.hasUndo()) {
                            throw new IllegalStateException("Cardano chain rolled back during scan; retry from durable intersection");
                        } else if (response.hasApply()) {
                            if (!reset || !response.getApply().hasCardano())
                                throw new IllegalStateException("Cardano stream did not establish its intersection");
                            var block = response.getApply();
                            var header = block.getCardano().getHeader();
                            if (header.getHash().size() != 32 || block.getNativeBytes().isEmpty())
                                throw new IllegalStateException("Cardano stream omitted original block identity");
                            // Origin is followed by the height-zero block.
                            if ((!blocks.isEmpty() || !intersection.getHash().isEmpty()) && header.getHeight() != previous + 1)
                                throw new IllegalStateException("Cardano block stream has a height gap");
                            if (header.getHeight() > throughHeight) break;
                            blocks.add(block);
                            previous = header.getHeight();
                            if (blocks.size() == limit || previous == throughHeight) break;
                        } else throw new IllegalStateException("Cardano stream action is absent");
                    }
                    if (!reset) throw new IllegalStateException("Cardano stream ended before intersection");
                    return List.copyOf(blocks);
                });
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Cardano block stream failed", e);
            } finally {
                context.cancel(null);
            }
        });
    }

    /** Native chain sync selects an exact stored intersection, including after a rollback. */
    public com.surprising.wallet.common.chain.ChainScanPoint commonPoint(
            List<com.surprising.wallet.common.chain.ChainScanPoint> candidates) {
        if (candidates.isEmpty() || candidates.size() > 1_000)
            throw new IllegalArgumentException("invalid Cardano intersection candidates");
        return call(channel -> {
            var context = Context.current().withCancellation();
            try {
                return context.call(() -> {
                    var request = Sync.FollowTipRequest.newBuilder();
                    candidates.forEach(point -> request.addIntersect(reference(point)));
                    var stream = sync(channel).followTip(request.build());
                    if (!stream.hasNext()) throw new IllegalStateException("Cardano intersection response is absent");
                    var response = stream.next();
                    if (!response.hasReset()) throw new IllegalStateException("Cardano intersection reset is absent");
                    return candidates.stream().filter(point -> samePoint(reference(point), response.getReset())).findFirst()
                            .orElseThrow(() -> new IllegalStateException("Cardano rollback exceeds stored intersections; manual reconciliation required"));
                });
            } catch (RuntimeException error) { throw error; }
            catch (Exception error) { throw new IllegalStateException("Cardano intersection lookup failed", error); }
            finally { context.cancel(null); }
        });
    }

    public Query.SearchUtxosResponse utxos(String address) {
        byte[] addressBytes = addressBytes(address);
        return call(channel -> {
            var predicate = Query.UtxoPredicate.newBuilder().setMatch(Query.AnyUtxoPattern.newBuilder()
                    .setCardano(Cardano.TxOutputPattern.newBuilder().setAddress(Cardano.AddressPattern.newBuilder()
                            .setExactAddress(ByteString.copyFrom(addressBytes))))).build();
            List<Query.AnyUtxoData> items = new ArrayList<>();
            var seenTokens = new HashSet<String>();
            var seenOutputs = new HashSet<Query.TxoRef>();
            String token = "";
            Query.ChainPoint snapshot = null;
            do {
                var request = Query.SearchUtxosRequest.newBuilder().setPredicate(predicate).setMaxItems(500);
                if (!token.isEmpty()) request.setStartToken(token);
                var page = query(channel).searchUtxos(request.build());
                if (!page.hasLedgerTip() || page.getLedgerTip().getHash().size() != 32)
                    throw new IllegalStateException("Cardano UTxO response has no ledger point");
                if (snapshot != null && !snapshot.equals(page.getLedgerTip()))
                    throw new IllegalStateException("Cardano UTxO pagination crossed ledger snapshots; retry");
                snapshot = page.getLedgerTip();
                for (var item : page.getItemsList()) {
                    if (!item.hasCardano() || !item.getCardano().getAddress().equals(ByteString.copyFrom(addressBytes))
                            || item.getTxoRef().getHash().size() != 32 || !seenOutputs.add(item.getTxoRef()))
                        throw new IllegalStateException("Cardano UTxO ownership or unique reference is invalid");
                    items.add(item);
                    if (items.size() > MAX_UTXOS) throw new IllegalStateException("Cardano UTxO snapshot exceeds limit");
                }
                token = page.getNextToken();
                if (!token.isEmpty() && !seenTokens.add(token))
                    throw new IllegalStateException("Cardano UTxO pagination repeated a token");
            } while (!token.isEmpty());
            return Query.SearchUtxosResponse.newBuilder().addAllItems(items).setLedgerTip(snapshot).build();
        });
    }

    public Query.ReadUtxosResponse readUtxos(List<Query.TxoRef> references) {
        if (references.isEmpty() || references.size() > MAX_UTXOS)
            throw new IllegalArgumentException("invalid Cardano UTxO reference count");
        return call(channel -> query(channel).readUtxos(Query.ReadUtxosRequest.newBuilder().addAllKeys(references).build()));
    }

    public String submit(byte[] signedTransaction) {
        if (signedTransaction == null || signedTransaction.length == 0 || signedTransaction.length > 65_536)
            throw new IllegalArgumentException("invalid Cardano signed transaction size");
        return call(channel -> {
            var response = SubmitServiceGrpc.newBlockingStub(channel).withDeadlineAfter(30, TimeUnit.SECONDS)
                    .submitTx(Submit.SubmitTxRequest.newBuilder().setTx(Submit.AnyChainTx.newBuilder()
                            .setRaw(ByteString.copyFrom(signedTransaction))).build());
            if (response.getRef().size() != 32) throw new IllegalStateException("Cardano submission hash is absent");
            return HexFormat.of().formatHex(response.getRef().toByteArray());
        });
    }

    public static Sync.BlockRef reference(Cardano.Block block) {
        return Sync.BlockRef.newBuilder().setSlot(block.getHeader().getSlot()).setHash(block.getHeader().getHash())
                .setHeight(block.getHeader().getHeight()).setTimestamp(block.getTimestamp()).build();
    }

    public static com.surprising.wallet.common.chain.ChainScanPoint point(Sync.BlockRef reference) {
        return new com.surprising.wallet.common.chain.ChainScanPoint(reference.getHeight(), reference.getSlot(),
                HexFormat.of().formatHex(reference.getHash().toByteArray()));
    }

    public static Sync.BlockRef reference(com.surprising.wallet.common.chain.ChainScanPoint point) {
        return Sync.BlockRef.newBuilder().setHeight(point.height()).setSlot(point.slot())
                .setHash(ByteString.copyFrom(HexFormat.of().parseHex(point.hash()))).build();
    }

    public static BigInteger integer(Cardano.BigInt value) {
        return switch (value.getBigIntCase()) {
            case INT -> BigInteger.valueOf(value.getInt());
            case BIG_U_INT -> new BigInteger(1, value.getBigUInt().toByteArray());
            case BIG_N_INT -> new BigInteger(1, value.getBigNInt().toByteArray()).negate().subtract(BigInteger.ONE);
            default -> throw new IllegalArgumentException("Cardano integer value is absent");
        };
    }

    public static byte[] addressBytes(String address) { return com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec.addressBytes(address); }

    private <T> T call(Function<Channel, T> action) {
        String network = profiles.findAccountByChain(CHAIN).orElseThrow().getNetwork();
        return nodes.withFailover(CHAIN, network, node -> callNode(node.getRpcUrl(), nodes.authHeaders(node), action));
    }

    private static <T> T callNode(String endpoint, Map<String, String> headers, Function<Channel, T> action) {
        URI uri = URI.create(endpoint.contains("://") ? endpoint : "grpcs://" + endpoint);
        boolean secure = switch (uri.getScheme()) {
            case "https", "grpcs" -> true;
            case "http", "grpc" -> false;
            default -> throw new IllegalArgumentException("invalid Cardano gRPC scheme");
        };
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")))
            throw new IllegalArgumentException("invalid Cardano gRPC endpoint");
        var builder = ManagedChannelBuilder.forAddress(uri.getHost(), uri.getPort() < 0 ? (secure ? 443 : 80) : uri.getPort())
                .maxInboundMessageSize(16 * 1024 * 1024);
        if (secure) builder.useTransportSecurity(); else builder.usePlaintext();
        ManagedChannel managed = builder.build();
        try {
            Channel channel = managed;
            if (!headers.isEmpty()) {
                Metadata metadata = new Metadata();
                headers.forEach((name, value) -> metadata.put(Metadata.Key.of(name.toLowerCase(Locale.ROOT),
                        Metadata.ASCII_STRING_MARSHALLER), value));
                channel = ClientInterceptors.intercept(managed, MetadataUtils.newAttachHeadersInterceptor(metadata));
            }
            return action.apply(channel);
        } finally {
            managed.shutdownNow();
            try { managed.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static QueryServiceGrpc.QueryServiceBlockingStub query(Channel channel) {
        return QueryServiceGrpc.newBlockingStub(channel).withDeadlineAfter(30, TimeUnit.SECONDS);
    }

    private static SyncServiceGrpc.SyncServiceBlockingStub sync(Channel channel) {
        return SyncServiceGrpc.newBlockingStub(channel).withDeadlineAfter(30, TimeUnit.SECONDS);
    }

    private static void requireRef(Sync.BlockRef ref) {
        if (ref.getHash().size() != 32 || ref.getSlot() < 0 || ref.getHeight() < 0)
            throw new IllegalArgumentException("Cardano block reference requires slot and 32-byte hash");
    }

    private static boolean samePoint(Sync.BlockRef a, Sync.BlockRef b) {
        return a.getSlot() == b.getSlot() && a.getHash().equals(b.getHash());
    }
}
