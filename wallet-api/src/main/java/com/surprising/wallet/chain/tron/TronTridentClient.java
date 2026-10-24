package com.surprising.wallet.chain.tron;

import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.ChainRpcNodeService;
import org.tron.trident.abi.datatypes.Type;
import org.tron.trident.abi.datatypes.Function;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.core.NodeType;
import org.tron.trident.core.exceptions.IllegalException;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.core.utils.Utils;
import org.tron.trident.proto.Response.Account;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Response;

import java.util.List;

import static org.tron.trident.core.Constant.TRANSACTION_DEFAULT_EXPIRATION_TIME;

/** TRON 节点访问与本地交易构建；节点请求经过配置节点的统一限流。 */
public class TronTridentClient implements AutoCloseable {
    private final ApiWrapper apiWrapper;
    private final ChainRpcNode fullNode;
    private final ChainRpcNode solidityNode;
    private final ChainRpcNodeService rpcNodeService;
    /**
     * 保存 {@code apiKey}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final String apiKey;
    public TronTridentClient(
            ChainRpcNode fullNode,
            ChainRpcNode solidityNode,
            ChainRpcNodeService rpcNodeService) {
        this.fullNode = java.util.Objects.requireNonNull(fullNode, "fullNode");
        this.solidityNode = java.util.Objects.requireNonNull(solidityNode, "solidityNode");
        this.rpcNodeService = java.util.Objects.requireNonNull(rpcNodeService, "rpcNodeService");
        this.apiKey = firstNonBlank(fullNode.getApiKey(), solidityNode.getApiKey());
        this.apiWrapper = new ApiWrapper(fullNode.getRpcUrl(), solidityNode.getRpcUrl(),
                "", this.apiKey == null ? "" : this.apiKey);
    }
    public synchronized Chain.Transaction createTrxTransfer(String from, String to, long amountSun) throws IllegalException {
        prepareLocalTransaction(apiWrapper);
        try {
            return apiWrapper.transfer(from, to, amountSun).getTransaction();
        } finally {
            apiWrapper.disableLocalCreate();
        }
    }

    public Chain.Transaction createContractCall(String owner, String contract, Function function, long feeLimitSun) {
        return callUnchecked(fullNode, () -> apiWrapper.triggerCall(owner, contract, function)
                .setFeeLimit(feeLimitSun).build());
    }

    public Chain.Transaction signTransaction(Chain.Transaction transaction, KeyPair keyPair) {
        return apiWrapper.signTransaction(transaction, keyPair);
    }

    public Response.TransactionExtention constantCall(String owner, String contract, Function function) {
        return callUnchecked(fullNode, () -> apiWrapper.triggerConstantContract(owner, contract, function));
    }

    public Response.EstimateEnergyMessage estimateEnergy(String owner, String contract, Function function, NodeType nodeType) {
        return callUnchecked(node(nodeType), () -> apiWrapper.estimateEnergy(owner, contract, function, nodeType));
    }
    public Chain.Block getNowBlock() throws Exception {
        return call(fullNode, apiWrapper::getNowBlock);
    }
    public Response.BlockExtention getBlockByNumber(long height) throws Exception {
        return call(fullNode, () -> apiWrapper.getBlockByNum(height));
    }
    public Response.TransactionInfo getTransactionInfo(String txId) throws Exception {
        return call(solidityNode, () -> apiWrapper.getTransactionInfoById(txId, NodeType.SOLIDITY_NODE));
    }
    public Response.TransactionInfo getTransactionInfo(String txId, NodeType nodeType) throws Exception {
        return call(node(nodeType), () -> apiWrapper.getTransactionInfoById(txId, nodeType));
    }
    public Chain.Transaction getTransactionById(String txId, NodeType nodeType) throws Exception {
        return call(node(nodeType), () -> apiWrapper.getTransactionById(txId, nodeType));
    }
    public Response.TransactionInfoList getTransactionInfoByBlockNum(long blockHeight, NodeType nodeType) throws Exception {
        return call(node(nodeType), () -> apiWrapper.getTransactionInfoByBlockNum(blockHeight, nodeType));
    }
    public Account getAccount(String base58Address, NodeType nodeType) {
        return callUnchecked(node(nodeType), () -> apiWrapper.getAccount(base58Address, nodeType));
    }
    public long getBalanceSun(String base58Address) {
        return callUnchecked(fullNode, () -> apiWrapper.getAccountBalance(base58Address));
    }
    public Response.AccountResourceMessage getResources(String base58Address) {
        return callUnchecked(fullNode, () -> apiWrapper.getAccountResource(base58Address));
    }
    public Response.AccountNetMessage getBandwidth(String base58Address) {
        return callUnchecked(fullNode, () -> apiWrapper.getAccountNet(base58Address));
    }
    public String broadcast(Chain.Transaction signedTransaction) {
        return callUnchecked(fullNode, () -> apiWrapper.broadcastTransaction(signedTransaction));
    }

    public Response.TransactionExtention deployContract(KeyPair keyPair,
                                                        String contractName,
                                                        String abiJson,
                                                        String bytecodeHex,
                                                        List<Type<?>> constructorArgs,
                                                        long feeLimitSun,
                                                        long consumeUserResourcePercent,
                                                        long originEnergyLimit,
                                                        long callValueSun) {
        ApiWrapper deployWrapper = new ApiWrapper(fullNode.getRpcUrl(), solidityNode.getRpcUrl(),
                keyPair.toPrivateKey(), apiKey == null ? "" : apiKey);
        try {
            prepareLocalTransaction(deployWrapper);
            return deployWrapper.deployContract(contractName, abiJson, bytecodeHex, constructorArgs,
                    feeLimitSun, consumeUserResourcePercent, originEnergyLimit, callValueSun, "", 0L);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("TRON RPC request failed", error);
        } finally {
            deployWrapper.close();
        }
    }

    /** SDK 的默认交易构建隐含两次 RPC；分别限流后沿用同一参考块与有效期规则在本地构建。 */
    private void prepareLocalTransaction(ApiWrapper target) {
        var solid = callUnchecked(solidityNode, () -> apiWrapper.getBlock(false, NodeType.SOLIDITY_NODE));
        var head = callUnchecked(fullNode, () -> apiWrapper.getBlock(false, NodeType.FULL_NODE));
        target.enableLocalCreate(Utils.getBlockId(solid),
                head.getBlockHeader().getRawData().getTimestamp() + TRANSACTION_DEFAULT_EXPIRATION_TIME);
    }

    /**
     * 关闭 {@code close} 对应的外部资源，确保连接和线程得到释放。
     */
    @Override
    public void close() {
        apiWrapper.close();
    }
    private <T> T call(ChainRpcNode node, ChainRpcNodeService.ProviderLimitedRequest<T> request) throws Exception {
        return rpcNodeService.withProviderLimit(node, request);
    }
    private <T> T callUnchecked(ChainRpcNode node, ChainRpcNodeService.ProviderLimitedRequest<T> request) {
        try {
            return call(node, request);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("TRON RPC request failed", e);
        }
    }
    private ChainRpcNode node(NodeType nodeType) {
        return NodeType.SOLIDITY_NODE.equals(nodeType) ? solidityNode : fullNode;
    }
    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
