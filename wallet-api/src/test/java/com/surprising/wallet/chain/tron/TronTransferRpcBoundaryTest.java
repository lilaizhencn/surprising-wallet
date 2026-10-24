package com.surprising.wallet.chain.tron;

import com.google.protobuf.Any;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.ChainRpcNodeService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.tron.trident.api.GrpcAPI;
import org.tron.trident.api.WalletGrpc;
import org.tron.trident.api.WalletSolidityGrpc;
import org.tron.trident.abi.datatypes.Function;
import org.tron.trident.core.NodeType;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Contract;
import org.tron.trident.proto.Response;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.tron.trident.core.Constant.TRANSACTION_DEFAULT_EXPIRATION_TIME;

class TronTransferRpcBoundaryTest {
    private static final KeyPair KEY = new KeyPair("0".repeat(63) + "7");
    private static final String RECIPIENT = new KeyPair("0".repeat(63) + "b").toBase58CheckAddress();
    private static final long BLOCK_TIME = 1_700_000_000_000L;

    @Test
    void nativePreparationLimitsEachNodeWithoutNestingAndPreservesTheTransfer() throws Exception {
        try (var f = new Fixture()) {
            var signed = new TronTransactionService().signTrxTransfer(f.client, KEY, RECIPIENT, 1_234_567L);
            assertEquals(List.of("solidity:block", "full:block"), f.requests);
            var raw = signed.transaction().getRawData();
            var transfer = raw.getContract(0).getParameter().unpack(Contract.TransferContract.class);
            assertEquals(1_234_567L, transfer.getAmount());
            assertEquals(1, signed.transaction().getSignatureCount());
            assertEquals(BLOCK_TIME + TRANSACTION_DEFAULT_EXPIRATION_TIME, raw.getExpiration());
            assertEquals(TronTransactionService.txId(signed.transaction()), signed.txId());
            assertArrayEquals(org.tron.trident.core.ApiWrapper.parseAddress(RECIPIENT).toByteArray(), transfer.getToAddress().toByteArray());
        }
    }

    @Test
    void tokenPreparationUsesLimitedFullNodeAndPreservesAbiAmountAndFee() throws Exception {
        try (var f = new Fixture()) {
            var signed = new TronTrc20Service().signTransfer(f.client, KEY, RECIPIENT, RECIPIENT,
                    new BigDecimal("1.234567"), 6, 12_500_000L);
            assertEquals(List.of("full:contract"), f.requests);
            assertEquals(12_500_000L, signed.transaction().getRawData().getFeeLimit());
            var contract = signed.transaction().getRawData().getContract(0).getParameter()
                    .unpack(Contract.TriggerSmartContract.class);
            String data = org.tron.trident.core.ApiWrapper.toHex(contract.getData().toByteArray());
            assertTrue(data.endsWith("0".repeat(58) + "12d687"));
            assertEquals(1, signed.transaction().getSignatureCount());
            assertEquals(TronTransactionService.txId(signed.transaction()), signed.txId());
        }
    }

    @Test
    void deploymentBuildsLocallyAfterSeparatelyLimitedReferenceReads() throws Exception {
        try (var f = new Fixture()) {
            var deployment = f.client.deployContract(KEY, "test", "[]", "60006000", List.of(),
                    12_500_000L, 100L, 10_000L, 0L);
            assertEquals(List.of("solidity:block", "full:block"), f.requests);
            assertEquals(12_500_000L, deployment.getTransaction().getRawData().getFeeLimit());
            assertEquals(Chain.Transaction.Contract.ContractType.CreateSmartContract,
                    deployment.getTransaction().getRawData().getContract(0).getType());
        }
    }

    @Test
    void constantCallsAndEnergyEstimatesUseTheirConfiguredNodeLimit() throws Exception {
        try (var f = new Fixture()) {
            var function = new Function("balanceOf", List.of(), List.of());
            f.client.constantCall(KEY.toBase58CheckAddress(), RECIPIENT, function);
            f.client.estimateEnergy(KEY.toBase58CheckAddress(), RECIPIENT, function, NodeType.FULL_NODE);
            f.client.estimateEnergy(KEY.toBase58CheckAddress(), RECIPIENT, function, NodeType.SOLIDITY_NODE);
            assertEquals(List.of("full:contract", "full:energy", "solidity:energy"), f.requests);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final List<String> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<String> limitedNode = new AtomicReference<>();
        final Server full;
        final Server solidity;
        final TronTridentClient client;

        Fixture() throws Exception {
            full = ServerBuilder.forPort(0).addService(new WalletGrpc.WalletImplBase() {
                @Override public void getBlock(GrpcAPI.BlockReq request, StreamObserver<Response.BlockExtention> out) {
                    observe("full", "block"); reply(out, block());
                }
                @Override public void triggerConstantContract(Contract.TriggerSmartContract request,
                                                              StreamObserver<Response.TransactionExtention> out) {
                    observe("full", "contract");
                    reply(out, Response.TransactionExtention.newBuilder().setTransaction(Chain.Transaction.newBuilder()
                            .setRawData(Chain.Transaction.raw.newBuilder().setTimestamp(BLOCK_TIME).addContract(
                                    Chain.Transaction.Contract.newBuilder().setType(Chain.Transaction.Contract.ContractType.TriggerSmartContract)
                                            .setParameter(Any.pack(request))))).build());
                }
                @Override public void estimateEnergy(Contract.TriggerSmartContract request, StreamObserver<Response.EstimateEnergyMessage> out) {
                    observe("full", "energy"); reply(out, Response.EstimateEnergyMessage.getDefaultInstance());
                }
            }).build().start();
            solidity = ServerBuilder.forPort(0).addService(new WalletSolidityGrpc.WalletSolidityImplBase() {
                @Override public void getBlock(GrpcAPI.BlockReq request, StreamObserver<Response.BlockExtention> out) {
                    observe("solidity", "block"); reply(out, block());
                }
                @Override public void estimateEnergy(Contract.TriggerSmartContract request, StreamObserver<Response.EstimateEnergyMessage> out) {
                    observe("solidity", "energy"); reply(out, Response.EstimateEnergyMessage.getDefaultInstance());
                }
            }).build().start();
            var limiter = mock(ChainRpcNodeService.class);
            var permit = new Semaphore(1);
            doAnswer(call -> {
                if (!permit.tryAcquire()) throw new AssertionError("nested provider limit would deadlock a single-permit provider");
                limitedNode.set(((ChainRpcNode) call.getArgument(0)).getNodeLabel());
                try { return call.getArgument(1, ChainRpcNodeService.ProviderLimitedRequest.class).execute(); }
                finally { limitedNode.set(null); permit.release(); }
            }).when(limiter).withProviderLimit(any(ChainRpcNode.class), any());
            client = new TronTridentClient(node("full", full.getPort()), node("solidity", solidity.getPort()), limiter);
        }

        private void observe(String purpose, String operation) {
            requests.add((purpose.equals(limitedNode.get()) ? purpose : "UNLIMITED-" + purpose) + ":" + operation);
        }

        private static ChainRpcNode node(String purpose, int port) {
            return ChainRpcNode.builder().nodeLabel(purpose).rpcUrl("127.0.0.1:" + port).build();
        }

        private static Response.BlockExtention block() {
            return Response.BlockExtention.newBuilder().setBlockHeader(Chain.BlockHeader.newBuilder()
                    .setRawData(Chain.BlockHeader.raw.newBuilder().setNumber(100L).setTimestamp(BLOCK_TIME))).build();
        }

        private static <T> void reply(StreamObserver<T> out, T response) { out.onNext(response); out.onCompleted(); }

        @Override public void close() throws Exception {
            client.close();
            full.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            solidity.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
