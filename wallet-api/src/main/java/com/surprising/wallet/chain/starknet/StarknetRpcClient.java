package com.surprising.wallet.chain.starknet;

import com.swmansion.starknet.data.Selector;
import com.swmansion.starknet.data.types.AddressFilter;
import com.swmansion.starknet.data.types.BlockId;
import com.swmansion.starknet.data.types.BlockTag;
import com.swmansion.starknet.data.types.Call;
import com.swmansion.starknet.data.types.EmittedEvent;
import com.swmansion.starknet.data.types.Felt;
import com.swmansion.starknet.data.types.FeltArray;
import com.swmansion.starknet.data.types.GetBlockHashAndNumberResponse;
import com.swmansion.starknet.data.types.GetEventsPayload;
import com.swmansion.starknet.data.types.GetEventsResult;
import com.swmansion.starknet.data.types.StarknetChainId;
import com.swmansion.starknet.data.types.TransactionReceipt;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.ChainRpcNodeService;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

import com.swmansion.starknet.provider.Provider;
import com.swmansion.starknet.provider.exceptions.RpcRequestFailedException;
import com.swmansion.starknet.provider.rpc.JsonRpcProvider;
import com.swmansion.starknet.service.http.OkHttpService;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Starknet JSON-RPC 客户端，统一处理节点认证、限流、故障转移和类型化 RPC 调用。
 */
@Service
public class StarknetRpcClient {
    /** ERC-20 Transfer 事件选择器。 */
    private static final Felt TRANSFER_SELECTOR = Selector.selectorFromName("Transfer");
    /** Starknet RPC 的“合约不存在”错误码。 */
    private static final int CONTRACT_NOT_FOUND_ERROR = 20;
    /** 单次事件查询的最大事件数。 */
    private static final int EVENT_CHUNK_SIZE = 1_000;
    /** RPC 客户端连接超时。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** RPC 客户端读取超时。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    /** RPC 节点服务。 */
    private final ChainRpcNodeService nodeService;

    /** 构造 Starknet RPC 客户端。 */
    public StarknetRpcClient(ChainRpcNodeService nodeService) {
        this.nodeService = nodeService;
    }

    /** 返回指定 Starknet 网络的最新区块高度和哈希。 */
    public BlockTip latest(AccountChainProfile profile) {
        return withProvider(profile, "rpc", provider -> {
            GetBlockHashAndNumberResponse response = provider.getBlockHashAndNumber().send();
            return new BlockTip(response.getBlockNumber(), response.getBlockHash().hexString());
        });
    }

    /** 按区块范围查询指定 ERC-20 合约的 Transfer 事件。 */
    public List<EmittedEvent> transferEvents(AccountChainProfile profile, String contractAddress,
                                              long fromBlock, long toBlock) {
        if (fromBlock > toBlock) {
            return List.of();
        }
        return withProvider(profile, "scan", provider -> {
            List<EmittedEvent> events = new ArrayList<>();
            String continuation = null;
            do {
                GetEventsPayload payload = new GetEventsPayload(
                        new BlockId.Number(Math.toIntExact(fromBlock)),
                        new BlockId.Number(Math.toIntExact(toBlock)),
                        new AddressFilter.Single(Felt.fromHex(contractAddress)),
                        List.of(List.of(TRANSFER_SELECTOR)), EVENT_CHUNK_SIZE, continuation);
                GetEventsResult result = provider.getEvents(payload).send();
                events.addAll(result.getEvents());
                continuation = result.getContinuationToken();
            } while (continuation != null && !continuation.isBlank());
            return events;
        });
    }

    /** 查询交易回执。交易未上链时返回异常，由调用方按待确认处理。 */
    public TransactionReceipt receipt(AccountChainProfile profile, String transactionHash) {
        return withProvider(profile, "rpc", provider -> provider.getTransactionReceipt(
                Felt.fromHex(transactionHash)).send());
    }

    /** 判断 counterfactual 地址是否已经部署为账户合约。 */
    public boolean isDeployed(AccountChainProfile profile, String address) {
        try {
            return withProvider(profile, "rpc", provider -> provider.getClassHashAt(
                    Felt.fromHex(address)).send().getValue().signum() > 0);
        } catch (RpcRequestFailedException error) {
            if (error.getCode() == CONTRACT_NOT_FOUND_ERROR) {
                return false;
            }
            throw error;
        }
    }

    /** 读取 Starknet 账户或代币的 uint256 余额。 */
    public BigInteger balance(AccountChainProfile profile, String contractAddress, String ownerAddress) {
        return withProvider(profile, "rpc", provider -> {
            Call call = new Call(Felt.fromHex(contractAddress), "balance_of",
                    List.of(Felt.fromHex(ownerAddress)));
            FeltArray values = provider.callContract(call, BlockTag.LATEST).send();
            if (values.size() < 2) {
                throw new IllegalStateException("Starknet balance_of returned less than uint256 values");
            }
            return values.get(0).getValue().add(values.get(1).getValue().shiftLeft(128));
        });
    }

    /** 返回指定网络的 SDK 链 ID。 */
    public StarknetChainId chainId(AccountChainProfile profile) {
        return withProvider(profile, "rpc", provider -> provider.getChainId().send());
    }

    /** 在节点服务的限流和故障转移保护下执行 SDK RPC 请求。 */
    public <T> T withProvider(AccountChainProfile profile, String purpose,
                               Function<Provider, T> action) {
        return nodeService.withFailover(profile.getChain(), profile.getNetwork(), purpose,
                node -> action.apply(provider(node)));
    }

    /** 为单个 RPC 节点构造带认证头的 SDK Provider。 */
    private Provider provider(ChainRpcNode node) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .writeTimeout(READ_TIMEOUT);
        var headers = nodeService.authHeaders(node);
        if (!headers.isEmpty()) {
            builder.addInterceptor(chain -> {
                okhttp3.Request.Builder request = chain.request().newBuilder();
                headers.forEach(request::header);
                return chain.proceed(request.build());
            });
        }
        return new JsonRpcProvider(node.getRpcUrl(), new OkHttpService(builder.build()));
    }

    /** 最新区块信息。 */
    public record BlockTip(long number, String hash) {
    }
}
