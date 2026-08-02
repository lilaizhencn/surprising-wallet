package com.surprising.wallet.chain.tron;

import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.ChainRpcNodeService;
import org.tron.trident.abi.datatypes.Type;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.core.NodeType;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.proto.Response.Account;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Response;

import java.util.List;

/**
 * 负责调用外部节点或基础设施服务，并统一处理通信结果。
 */
public class TronTridentClient implements AutoCloseable {
    /**
     * 保存 {@code apiWrapper}，用于承载当前对象的运行配置或业务数据。
     */
    private final ApiWrapper apiWrapper;
    /**
     * 保存 {@code fullNode}，用于承载当前对象的运行配置或业务数据。
     */
    private final ChainRpcNode fullNode;
    /**
     * 保存 {@code solidityNode}，用于标识交易、区块或业务记录。
     */
    private final ChainRpcNode solidityNode;
    /**
     * 保存 {@code rpcNodeService}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainRpcNodeService rpcNodeService;
    /**
     * 保存 {@code apiKey}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final String apiKey;
    /**
     * 构造 {@code TronTridentClient}，初始化该组件运行所需的状态和依赖。
     */
    public TronTridentClient(String fullNode, String solidityNode, String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.apiWrapper = new ApiWrapper(fullNode, solidityNode, "", this.apiKey);
        this.fullNode = ChainRpcNode.builder()
                .nodeLabel("tron-full-node")
                .rpcUrl(fullNode)
                .build();
        this.solidityNode = ChainRpcNode.builder()
                .nodeLabel("tron-solidity-node")
                .rpcUrl(solidityNode)
                .build();
        this.rpcNodeService = null;
    }

    /**
     * 构造 {@code TronTridentClient}，初始化该组件运行所需的状态和依赖。
     */
    public TronTridentClient(
            ChainRpcNode fullNode,
            ChainRpcNode solidityNode,
            ChainRpcNodeService rpcNodeService) {
        this.apiKey = firstNonBlank(fullNode.getApiKey(), solidityNode.getApiKey());
        this.apiWrapper = new ApiWrapper(fullNode.getRpcUrl(), solidityNode.getRpcUrl(),
                "", this.apiKey == null ? "" : this.apiKey);
        this.fullNode = fullNode;
        this.solidityNode = solidityNode;
        this.rpcNodeService = rpcNodeService;
    }
    /**
     * 执行 {@code api} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public ApiWrapper api() {
        return apiWrapper;
    }
    /**
     * 获取或查询 {@code getNowBlock} 对应的数据，供调用方读取当前状态。
     */
    public Chain.Block getNowBlock() throws Exception {
        return call(fullNode, apiWrapper::getNowBlock);
    }
    /**
     * 获取或查询 {@code getBlockByNumber} 对应的数据，供调用方读取当前状态。
     */
    public Response.BlockExtention getBlockByNumber(long height) throws Exception {
        return call(fullNode, () -> apiWrapper.getBlockByNum(height));
    }
    /**
     * 获取或查询 {@code getTransactionInfo} 对应的数据，供调用方读取当前状态。
     */
    public Response.TransactionInfo getTransactionInfo(String txId) throws Exception {
        return call(solidityNode, () -> apiWrapper.getTransactionInfoById(txId, NodeType.SOLIDITY_NODE));
    }
    /**
     * 获取或查询 {@code getTransactionInfo} 对应的数据，供调用方读取当前状态。
     */
    public Response.TransactionInfo getTransactionInfo(String txId, NodeType nodeType) throws Exception {
        return call(node(nodeType), () -> apiWrapper.getTransactionInfoById(txId, nodeType));
    }
    /**
     * 获取或查询 {@code getTransactionById} 对应的数据，供调用方读取当前状态。
     */
    public Chain.Transaction getTransactionById(String txId, NodeType nodeType) throws Exception {
        return call(node(nodeType), () -> apiWrapper.getTransactionById(txId, nodeType));
    }
    /**
     * 获取或查询 {@code getTransactionInfoByBlockNum} 对应的数据，供调用方读取当前状态。
     */
    public Response.TransactionInfoList getTransactionInfoByBlockNum(long blockHeight, NodeType nodeType) throws Exception {
        return call(node(nodeType), () -> apiWrapper.getTransactionInfoByBlockNum(blockHeight, nodeType));
    }
    /**
     * 获取或查询 {@code getAccount} 对应的数据，供调用方读取当前状态。
     */
    public Account getAccount(String base58Address, NodeType nodeType) {
        return callUnchecked(node(nodeType), () -> apiWrapper.getAccount(base58Address, nodeType));
    }
    /**
     * 获取或查询 {@code getBalanceSun} 对应的数据，供调用方读取当前状态。
     */
    public long getBalanceSun(String base58Address) {
        return callUnchecked(fullNode, () -> apiWrapper.getAccountBalance(base58Address));
    }
    /**
     * 获取或查询 {@code getResources} 对应的数据，供调用方读取当前状态。
     */
    public Response.AccountResourceMessage getResources(String base58Address) {
        return callUnchecked(fullNode, () -> apiWrapper.getAccountResource(base58Address));
    }
    /**
     * 获取或查询 {@code getBandwidth} 对应的数据，供调用方读取当前状态。
     */
    public Response.AccountNetMessage getBandwidth(String base58Address) {
        return callUnchecked(fullNode, () -> apiWrapper.getAccountNet(base58Address));
    }
    /**
     * 发送或广播 {@code broadcast} 对应的链上请求，并返回节点处理结果。
     */
    public String broadcast(Chain.Transaction signedTransaction) {
        return callUnchecked(fullNode, () -> apiWrapper.broadcastTransaction(signedTransaction));
    }

    /**
     * 执行 {@code deployContract} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Response.TransactionExtention deployContract(KeyPair keyPair,
                                                        String contractName,
                                                        String abiJson,
                                                        String bytecodeHex,
                                                        List<Type<?>> constructorArgs,
                                                        long feeLimitSun,
                                                        long consumeUserResourcePercent,
                                                        long originEnergyLimit,
                                                        long callValueSun) {
        return callUnchecked(fullNode, () -> {
            ApiWrapper deployWrapper = new ApiWrapper(fullNode.getRpcUrl(), solidityNode.getRpcUrl(),
                    keyPair.toPrivateKey(), apiKey == null ? "" : apiKey);
            try {
                return deployWrapper.deployContract(contractName, abiJson, bytecodeHex, constructorArgs,
                        feeLimitSun, consumeUserResourcePercent, originEnergyLimit, callValueSun, "", 0L);
            } finally {
                deployWrapper.close();
            }
        });
    }

    /**
     * 关闭 {@code close} 对应的外部资源，确保连接和线程得到释放。
     */
    @Override
    public void close() {
        apiWrapper.close();
    }
    /**
     * 执行 {@code call} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private <T> T call(ChainRpcNode node, ChainRpcNodeService.ProviderLimitedRequest<T> request) throws Exception {
        if (rpcNodeService == null) {
            return request.execute();
        }
        return rpcNodeService.withProviderLimit(node, request);
    }
    /**
     * 执行 {@code callUnchecked} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private <T> T callUnchecked(ChainRpcNode node, ChainRpcNodeService.ProviderLimitedRequest<T> request) {
        try {
            return call(node, request);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("TRON RPC request failed", e);
        }
    }
    /**
     * 执行 {@code node} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private ChainRpcNode node(NodeType nodeType) {
        return NodeType.SOLIDITY_NODE.equals(nodeType) ? solidityNode : fullNode;
    }
    /**
     * 执行 {@code firstNonBlank} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
