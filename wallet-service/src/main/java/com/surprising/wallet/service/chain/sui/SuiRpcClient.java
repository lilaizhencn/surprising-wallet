package com.surprising.wallet.service.chain.sui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import com.google.protobuf.FieldMask;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import sui.rpc.v2.BalanceChangeOuterClass;
import sui.rpc.v2.BcsOuterClass;
import sui.rpc.v2.ExecutedTransactionOuterClass;
import sui.rpc.v2.LedgerServiceGrpc;
import sui.rpc.v2.LedgerServiceOuterClass;
import sui.rpc.v2.ObjectOuterClass;
import sui.rpc.v2.Signature;
import sui.rpc.v2.StateServiceGrpc;
import sui.rpc.v2.StateServiceOuterClass;
import sui.rpc.v2.TransactionExecutionServiceGrpc;
import sui.rpc.v2.TransactionExecutionServiceOuterClass;
import sui.rpc.v2.TransactionOuterClass;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
public
class SuiRpcClient {
    private static final String CHAIN = "SUI";    private static final long RPC_TIMEOUT_SECONDS = 30L;    private static final FieldMask TRANSACTION_READ_MASK = FieldMask.newBuilder()
            .addPaths("digest")
            .addPaths("transaction.sender")
            .addPaths("effects.status")
            .addPaths("effects.gas_used")
            .addPaths("checkpoint")
            .addPaths("balance_changes")
            .build();
    private static final FieldMask COIN_READ_MASK = FieldMask.newBuilder()
            .addPaths("object_id")
            .addPaths("version")
            .addPaths("digest")
            .addPaths("object_type")
            .addPaths("balance")
            .build();
    private static final FieldMask CHECKPOINT_TRANSACTION_READ_MASK = FieldMask.newBuilder()
            .addPaths("sequence_number")
            .addPaths("transactions.digest")
            .addPaths("transactions.transaction.sender")
            .addPaths("transactions.effects.status")
            .addPaths("transactions.effects.gas_used")
            .addPaths("transactions.checkpoint")
            .addPaths("transactions.balance_changes")
            .build();
    public static final String SUI_COIN_TYPE = "0x2::sui::SUI";
    private final ObjectMapper objectMapper;    private final ChainJdbcRepository repository;    private final ChainRpcNodeService rpcNodeService;    private final String fixedGrpcEndpoint;

    @Autowired
    public SuiRpcClient(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {
        this(new ObjectMapper(), repository, rpcNodeService, null);
    }

    SuiRpcClient(ObjectMapper objectMapper, String grpcEndpoint) {
        this(objectMapper, null, null, grpcEndpoint);
    }

    private SuiRpcClient(ObjectMapper objectMapper, ChainJdbcRepository repository,
                         ChainRpcNodeService rpcNodeService, String fixedGrpcEndpoint) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.rpcNodeService = rpcNodeService;
        this.fixedGrpcEndpoint = fixedGrpcEndpoint;
    }
    public long latestCheckpoint() {
        return call(context -> ledger(context).getServiceInfo(
                        LedgerServiceOuterClass.GetServiceInfoRequest.getDefaultInstance()))
                .getCheckpointHeight();
    }
    public long referenceGasPrice() {
        LedgerServiceOuterClass.GetEpochRequest request = LedgerServiceOuterClass.GetEpochRequest.newBuilder()
                .setReadMask(FieldMask.newBuilder().addPaths("reference_gas_price"))
                .build();
        return call(context -> ledger(context).getEpoch(request)).getEpoch().getReferenceGasPrice();
    }
    public BigDecimal balance(String owner, String coinType) {
        StateServiceOuterClass.GetBalanceRequest request = StateServiceOuterClass.GetBalanceRequest.newBuilder()
                .setOwner(SuiHex.normalizeAddress(owner))
                .setCoinType(normalizeCoinType(coinType))
                .build();
        long value = call(context -> state(context).getBalance(request)).getBalance().getBalance();
        return unsignedBigDecimal(value);
    }
    public List<SuiCoin> coins(String owner, String coinType, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return call(context -> listCoins(context, owner, coinType, limit));
    }
    public List<JsonNode> checkpointTransactions(long startCheckpoint, long endCheckpoint) {
        if (endCheckpoint < startCheckpoint) {
            return List.of();
        }
        return call(context -> {
            LedgerServiceGrpc.LedgerServiceBlockingStub ledger = ledger(context);
            List<JsonNode> transactions = new ArrayList<>();
            for (long checkpoint = startCheckpoint; checkpoint <= endCheckpoint; checkpoint++) {
                LedgerServiceOuterClass.GetCheckpointRequest request =
                        LedgerServiceOuterClass.GetCheckpointRequest.newBuilder()
                                .setSequenceNumber(checkpoint)
                                .setReadMask(CHECKPOINT_TRANSACTION_READ_MASK)
                                .build();
                LedgerServiceOuterClass.GetCheckpointResponse response = ledger.getCheckpoint(request);
                if (!response.hasCheckpoint()) {
                    continue;
                }
                for (ExecutedTransactionOuterClass.ExecutedTransaction source
                        : response.getCheckpoint().getTransactionsList()) {
                    ObjectNode transaction = toLegacyTransaction(source);
                    transaction.put("checkpoint", checkpoint);
                    transactions.add(transaction);
                }
            }
            return transactions;
        });
    }
    public JsonNode transactionBlock(String digest) {
        LedgerServiceOuterClass.GetTransactionRequest request =
                LedgerServiceOuterClass.GetTransactionRequest.newBuilder()
                        .setDigest(digest)
                        .setReadMask(TRANSACTION_READ_MASK)
                        .build();
        LedgerServiceOuterClass.GetTransactionResponse response;
        try {
            response = call(context -> ledger(context).getTransaction(request));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return objectMapper.createObjectNode();
            }
            throw e;
        }
        return response.hasTransaction()
                ? toLegacyTransaction(response.getTransaction())
                : objectMapper.createObjectNode();
    }
    public JsonNode executeSignedTransaction(String txBytesBase64, String signatureBase64) {
        BcsOuterClass.Bcs transactionBcs = BcsOuterClass.Bcs.newBuilder()
                .setValue(ByteString.copyFrom(Base64.getDecoder().decode(txBytesBase64)))
                .build();
        BcsOuterClass.Bcs signatureBcs = BcsOuterClass.Bcs.newBuilder()
                .setValue(ByteString.copyFrom(Base64.getDecoder().decode(signatureBase64)))
                .build();
        TransactionExecutionServiceOuterClass.ExecuteTransactionRequest request =
                TransactionExecutionServiceOuterClass.ExecuteTransactionRequest.newBuilder()
                        .setTransaction(TransactionOuterClass.Transaction.newBuilder().setBcs(transactionBcs))
                        .addSignatures(Signature.UserSignature.newBuilder().setBcs(signatureBcs))
                        .setReadMask(TRANSACTION_READ_MASK)
                        .build();
        TransactionExecutionServiceOuterClass.ExecuteTransactionResponse response =
                call(context -> execution(context).executeTransaction(request));
        if (!response.hasTransaction()) {
            throw new IllegalStateException("Sui gRPC execution returned no transaction");
        }
        JsonNode transaction = toLegacyTransaction(response.getTransaction());
        if (!"success".equals(transaction.path("effects").path("status").path("status").asText())) {
            throw new IllegalStateException("Sui transaction execution failed: "
                    + transaction.path("effects").path("status").path("error").asText("unknown error"));
        }
        return transaction;
    }
    private List<SuiCoin> listCoins(GrpcContext context, String owner, String coinType, int limit) {
        List<SuiCoin> result = new ArrayList<>();
        ByteString pageToken = ByteString.EMPTY;
        String objectType = "0x2::coin::Coin<" + normalizeCoinType(coinType) + ">";
        do {
            StateServiceOuterClass.ListOwnedObjectsRequest.Builder request =
                    StateServiceOuterClass.ListOwnedObjectsRequest.newBuilder()
                            .setOwner(SuiHex.normalizeAddress(owner))
                            .setPageSize(Math.min(limit - result.size(), 1_000))
                            .setReadMask(COIN_READ_MASK)
                            .setObjectType(objectType);
            if (!pageToken.isEmpty()) {
                request.setPageToken(pageToken);
            }
            StateServiceOuterClass.ListOwnedObjectsResponse response =
                    state(context).listOwnedObjects(request.build());
            for (ObjectOuterClass.Object item : response.getObjectsList()) {
                result.add(new SuiCoin(item.getObjectId(), Long.toUnsignedString(item.getVersion()),
                        item.getDigest(), unsignedBigDecimal(item.getBalance())));
                if (result.size() == limit) {
                    break;
                }
            }
            pageToken = response.hasNextPageToken() ? response.getNextPageToken() : ByteString.EMPTY;
        } while (!pageToken.isEmpty() && result.size() < limit);
        return result;
    }
    private ObjectNode toLegacyTransaction(ExecutedTransactionOuterClass.ExecutedTransaction source) {
        ObjectNode transaction = objectMapper.createObjectNode();
        transaction.put("digest", source.getDigest());
        transaction.put("checkpoint", source.hasCheckpoint() ? source.getCheckpoint() : 0L);
        transaction.putObject("transaction").putObject("data")
                .put("sender", source.hasTransaction() ? source.getTransaction().getSender() : "0x0");

        ArrayNode balanceChanges = transaction.putArray("balanceChanges");
        for (BalanceChangeOuterClass.BalanceChange change : source.getBalanceChangesList()) {
            ObjectNode legacyChange = balanceChanges.addObject();
            legacyChange.putObject("owner").put("AddressOwner", change.getAddress());
            legacyChange.put("coinType", change.getCoinType());
            legacyChange.put("amount", change.getAmount());
        }

        ObjectNode effects = transaction.putObject("effects");
        ObjectNode status = effects.putObject("status");
        boolean success = source.hasEffects() && source.getEffects().hasStatus()
                && source.getEffects().getStatus().getSuccess();
        status.put("status", success ? "success" : "failure");
        if (!success) {
            String error = source.hasEffects() && source.getEffects().hasStatus()
                    && source.getEffects().getStatus().hasError()
                    ? source.getEffects().getStatus().getError().getDescription()
                    : "unknown error";
            status.put("error", error);
        }
        ObjectNode gasUsed = effects.putObject("gasUsed");
        if (source.hasEffects() && source.getEffects().hasGasUsed()) {
            gasUsed.put("computationCost", source.getEffects().getGasUsed().getComputationCost());
            gasUsed.put("storageCost", source.getEffects().getGasUsed().getStorageCost());
            gasUsed.put("storageRebate", source.getEffects().getGasUsed().getStorageRebate());
        } else {
            gasUsed.put("computationCost", 0L);
            gasUsed.put("storageCost", 0L);
            gasUsed.put("storageRebate", 0L);
        }
        return transaction;
    }
    private <T> T call(Function<GrpcContext, T> request) {
        if (fixedGrpcEndpoint != null && !fixedGrpcEndpoint.isBlank()) {
            return callNode(fixedGrpcEndpoint, Map.of(), request);
        }
        String network = repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN))
                .getNetwork();
        return rpcNodeService.withFailover(CHAIN, network,
                node -> callNode(node.getRpcUrl(), rpcNodeService.authHeaders(node), request));
    }

    private <T> T callNode(String endpoint, Map<String, String> authHeaders,
                           Function<GrpcContext, T> request) {
        Endpoint parsed = Endpoint.parse(endpoint);
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget(parsed.target());
        if (parsed.secure()) {
            builder.useTransportSecurity();
        } else {
            builder.usePlaintext();
        }
        ManagedChannel managedChannel = builder.build();
        try {
            Channel channel = managedChannel;
            if (!authHeaders.isEmpty()) {
                Metadata metadata = new Metadata();
                authHeaders.forEach((name, value) -> metadata.put(
                        Metadata.Key.of(name.toLowerCase(Locale.ROOT), Metadata.ASCII_STRING_MARSHALLER), value));
                channel = ClientInterceptors.intercept(
                        managedChannel, MetadataUtils.newAttachHeadersInterceptor(metadata));
            }
            return request.apply(new GrpcContext(channel));
        } finally {
            managedChannel.shutdownNow();
            try {
                managedChannel.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    private static LedgerServiceGrpc.LedgerServiceBlockingStub ledger(GrpcContext context) {
        return LedgerServiceGrpc.newBlockingStub(context.channel())
                .withDeadlineAfter(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    private static StateServiceGrpc.StateServiceBlockingStub state(GrpcContext context) {
        return StateServiceGrpc.newBlockingStub(context.channel())
                .withDeadlineAfter(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static TransactionExecutionServiceGrpc.TransactionExecutionServiceBlockingStub execution(
            GrpcContext context) {
        return TransactionExecutionServiceGrpc.newBlockingStub(context.channel())
                .withDeadlineAfter(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    private static BigDecimal unsignedBigDecimal(long value) {
        return new BigDecimal(Long.toUnsignedString(value));
    }
    private static String normalizeCoinType(String value) {
        String[] parts = value.split("::");
        if (parts.length != 3) {
            return value;
        }
        return SuiHex.normalizeAddress(parts[0]) + "::" + parts[1] + "::" + parts[2];
    }
    private record GrpcContext(Channel channel) {
    }
    private record Endpoint(String target, boolean secure) {
        private static Endpoint parse(String value) {
            String endpoint = value == null ? "" : value.trim();
            if (endpoint.isBlank()) {
                throw new IllegalArgumentException("Sui gRPC endpoint is blank");
            }
            if (endpoint.startsWith("https://") || endpoint.startsWith("http://")) {
                URI uri = URI.create(endpoint);
                boolean secure = "https".equalsIgnoreCase(uri.getScheme());
                int port = uri.getPort() >= 0 ? uri.getPort() : secure ? 443 : 80;
                return new Endpoint(uri.getHost() + ":" + port, secure);
            }
            if (endpoint.startsWith("grpc://")) {
                return new Endpoint(endpoint.substring("grpc://".length()).replaceAll("/+$", ""), false);
            }
            if (endpoint.startsWith("grpcs://")) {
                return new Endpoint(endpoint.substring("grpcs://".length()).replaceAll("/+$", ""), true);
            }
            String target = endpoint.replaceAll("/+$", "");
            return new Endpoint(target, target.endsWith(":443"));
        }
    }
    public record SuiCoin(String objectId, String version, String digest, BigDecimal balance) {
    }
}
