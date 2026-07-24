package com.surprising.wallet.chain.evm;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.EvmTransactionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.config.AccountSecp256k1KeyService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.bitcoinj.crypto.ECKey;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * EVM 兼容链的交易服务，负责原生币和 ERC-20 代币的转账发送与确认。
 *
 * <p>该类通过 Web3j 与 EVM 链节点交互，支持：
 * <ul>
 *   <li>原生币转账（ETH、BNB 等）</li>
 *   <li>ERC-20 代币转账（通过 {@link EvmTransactionBuilder} 构造 transfer payload）</li>
 *   <li>归集手续费储备估算</li>
 *   <li>提现与归集交易的链上确认</li>
 * </ul>
 *
 * <p>使用节点故障转移机制（{@link ChainRpcNodeService#withFailover}）保证高可用，
 * 通过数据库 nonce 预留（{@link ChainJdbcRepository#reserveEvmNonce}）防止 nonce 冲突。
 *
 * @see EvmTransactionBuilder
 * @see com.surprising.wallet.config.ChainRpcNodeService
 */
@Service
@RequiredArgsConstructor
public class EvmAccountTransactionService {

    /** 1 个原生币对应的 Wei 数量（10^18） */
    private static final BigInteger WEI_PER_NATIVE = new BigInteger("1000000000000000000");

    /** 原生币转账 Gas 上限 */
    private static final BigInteger NATIVE_GAS_LIMIT = BigInteger.valueOf(21_000L);

    /** ERC-20 代币转账 Gas 上限 */
    private static final BigInteger TOKEN_GAS_LIMIT = BigInteger.valueOf(65_000L);

    /** 归集手续费估算的安全系数（2x） */
    private static final BigInteger COLLECTION_FEE_SAFETY_MULTIPLIER = BigInteger.TWO;

    /** 链配置数据库访问 */
    private final ChainJdbcRepository repository;

    /** RPC 节点故障转移服务 */
    private final ChainRpcNodeService rpcNodeService;

    /** secp256k1 密钥服务 */
    private final AccountSecp256k1KeyService keyService;

    /** EVM 交易数据构造器 */
    private final EvmTransactionBuilder transactionBuilder;

    /**
     * 发送原生币转账。
     *
     * @param chain     链标识
     * @param from      发送方地址记录
     * @param toAddress 接收方地址
     * @param amount    转账金额（原生币单位）
     * @return 交易哈希
     */
    public String sendNative(String chain, ChainAddressRecord from, String toAddress, BigDecimal amount) {
        AccountChainProfile profile = profile(chain);
        BigInteger valueWei = amount.movePointRight(18).toBigIntegerExact();
        return withWeb3(profile, web3j -> {
            BigInteger gasPrice = gasPrice(web3j);
            BigInteger chainNonce = pendingNonce(web3j, from.getAddress());
            BigInteger nonce = BigInteger.valueOf(repository.reserveEvmNonce(
                    chain, normalize(from.getAddress()), chainNonce.longValueExact()));
            RawTransaction tx = RawTransaction.createEtherTransaction(
                    nonce, gasPrice, NATIVE_GAS_LIMIT, toAddress, valueWei);
            String txHash = broadcast(web3j, profile.getChainId(), tx, credentials(profile, from));
            record(chain, txHash, from.getAddress(), toAddress, nativeSymbol(profile), null,
                    amount, fee(gasPrice, NATIVE_GAS_LIMIT), nonce.longValue(), "SENT", null);
            return txHash;
        });
    }

    /**
     * 发送 ERC-20 代币转账。
     *
     * @param chain     链标识
     * @param from      发送方地址记录
     * @param token     代币定义（含合约地址）
     * @param toAddress 接收方地址
     * @param amount    转账金额（代币单位）
     * @return 交易哈希
     */
    public String sendToken(String chain, ChainAddressRecord from, TokenDefinition token,
                            String toAddress, BigDecimal amount) {
        AccountChainProfile profile = profile(chain);
        return withWeb3(profile, web3j -> {
            BigInteger gasPrice = gasPrice(web3j);
            BigInteger chainNonce = pendingNonce(web3j, from.getAddress());
            BigInteger nonce = BigInteger.valueOf(repository.reserveEvmNonce(
                    chain, normalize(from.getAddress()), chainNonce.longValueExact()));
            String data = transactionBuilder.buildErc20TransferPayload(toAddress, amount, token);
            RawTransaction tx = RawTransaction.createTransaction(
                    nonce, gasPrice, TOKEN_GAS_LIMIT, token.getContractAddress(), BigInteger.ZERO, data);
            String txHash = broadcast(web3j, profile.getChainId(), tx, credentials(profile, from));
            record(chain, txHash, from.getAddress(), toAddress, token.getSymbol(), token.getContractAddress(),
                    amount, fee(gasPrice, TOKEN_GAS_LIMIT), nonce.longValue(), "SENT", data);
            return txHash;
        });
    }
    /**
     * 估算归集操作所需的手续费储备。
     *
     * <p>按 1 笔原生币转账 + N 笔代币转账计算总 Gas，乘以 2x 安全系数。
     *
     * @param chain             链标识
     * @param enabledTokenCount 已启用的代币数量
     * @return 估算的手续费（原生币单位）
     */
    public BigDecimal estimateCollectionFeeReserve(String chain, int enabledTokenCount) {
        AccountChainProfile profile = profile(chain);
        int tokenCount = Math.max(0, enabledTokenCount);
        return withWeb3(profile, web3j -> {
            BigInteger totalGas = NATIVE_GAS_LIMIT.add(
                    TOKEN_GAS_LIMIT.multiply(BigInteger.valueOf(tokenCount)));
            return fee(gasPrice(web3j), totalGas.multiply(COLLECTION_FEE_SAFETY_MULTIPLIER));
        });
    }

    /**
     * 确认提现交易（自动获取租户 ID）。
     *
     * @param chain       链标识
     * @param orderNo     提现订单号
     * @param symbol      资产符号
     * @param accountId   账户 ID
     * @param debitAmount 扣款金额
     * @return true 表示确认并结算成功
     */
    public boolean confirmWithdrawal(String chain, String orderNo, String symbol,
                                     String accountId, BigDecimal debitAmount) {
        return confirmWithdrawal(repository.requireWithdrawalTenant(chain, orderNo),
                chain, orderNo, symbol, accountId, debitAmount);
    }

    /**
     * 确认提现交易（指定租户 ID）。
     *
     * <p>从链上获取交易回执，检查确认数是否满足要求，满足后执行结算。
     * 如果交易状态为失败（status != OK），抛出异常。
     *
     * @param tenantId    租户 ID
     * @param chain       链标识
     * @param orderNo     提现订单号
     * @param symbol      资产符号
     * @param accountId   账户 ID
     * @param debitAmount 扣款金额
     * @return true 表示确认并结算成功
     */
    public boolean confirmWithdrawal(java.util.UUID tenantId, String chain, String orderNo, String symbol,
                                     String accountId, BigDecimal debitAmount) {
        String txHash = repository.findWithdrawalTxHash(tenantId, chain, orderNo).orElseThrow();
        TransactionReceipt receipt = confirmedReceipt(chain, txHash).orElse(null);
        if (receipt == null) {
            return false;
        }
        if (!receipt.isStatusOK()) {
            throw new IllegalStateException("EVM transaction failed: " + txHash);
        }
        markConfirmed(chain, txHash, receipt);
        if (repository.confirmWithdrawalAndSettle(
                tenantId, chain, orderNo, txHash, symbol, accountId, debitAmount)) {
            return true;
        }
        return false;
    }
    /**
     * 确认归集交易。
     *
     * @param tenantId      租户 ID
     * @param chain         链标识
     * @param collectionNo  归集编号
     * @return true 表示确认成功
     */
    public boolean confirmCollection(java.util.UUID tenantId, String chain, String collectionNo) {
        String txHash = repository.findCollectionTxHash(tenantId, chain, collectionNo).orElseThrow();
        TransactionReceipt receipt = confirmedReceipt(chain, txHash).orElse(null);
        if (receipt == null) {
            return false;
        }
        if (!receipt.isStatusOK()) {
            throw new IllegalStateException("EVM collection transaction failed: " + txHash);
        }
        markConfirmed(chain, txHash, receipt);
        if (repository.markCollectionConfirmed(tenantId, chain, collectionNo, txHash) == 1) {
            return true;
        }
        return false;
    }
    private Optional<TransactionReceipt> confirmedReceipt(String chain, String txHash) {
        AccountChainProfile profile = profile(chain);
        return withWeb3(profile, web3j -> {
            Optional<TransactionReceipt> receipt = web3j.ethGetTransactionReceipt(txHash)
                    .send()
                    .getTransactionReceipt();
            if (receipt.isEmpty() || receipt.get().getBlockNumber() == null) {
                return Optional.empty();
            }
            BigInteger latest = web3j.ethBlockNumber().send().getBlockNumber();
            BigInteger confirmations = latest.subtract(receipt.get().getBlockNumber()).add(BigInteger.ONE);
            int required = Math.max(1, profile.getWithdrawConfirmations());
            return confirmations.compareTo(BigInteger.valueOf(required)) >= 0 ? receipt : Optional.empty();
        });
    }
    private void markConfirmed(String chain, String txHash, TransactionReceipt receipt) {
        BigInteger gasUsed = receipt.getGasUsed() == null ? BigInteger.ZERO : receipt.getGasUsed();
        BigInteger effectiveGasPrice = receipt.getEffectiveGasPrice() == null
                || receipt.getEffectiveGasPrice().isBlank()
                ? BigInteger.ZERO
                : Numeric.decodeQuantity(receipt.getEffectiveGasPrice());
        repository.recordEvmTransaction(EvmTransactionRecord.builder()
                .chain(chain)
                .txHash(txHash)
                .fromAddress(receipt.getFrom())
                .toAddress(receipt.getTo())
                .assetSymbol(nativeSymbol(profile(chain)))
                .amount(BigDecimal.ZERO)
                .fee(fee(effectiveGasPrice, gasUsed))
                .blockHeight(receipt.getBlockNumber() == null ? null : receipt.getBlockNumber().longValue())
                .confirmations(profile(chain).getWithdrawConfirmations())
                .status("CONFIRMED")
                .rawPayload(receipt.toString())
                .build());
    }
    private String broadcast(Web3j web3j, Long chainId, RawTransaction tx, Credentials credentials) throws Exception {
        byte[] signed = TransactionEncoder.signMessage(tx, chainId, credentials);
        EthSendTransaction sent = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();
        if (sent.hasError()) {
            throw new IllegalStateException(sent.getError().getMessage());
        }
        return sent.getTransactionHash();
    }
    private Credentials credentials(AccountChainProfile profile, ChainAddressRecord from) {
        ECKey ecKey = keyService.key(profile, from);
        return Credentials.create(Numeric.toHexStringNoPrefixZeroPadded(ecKey.getPrivKey(), 64));
    }
    private BigInteger gasPrice(Web3j web3j) throws Exception {
        return web3j.ethGasPrice().send().getGasPrice();
    }
    private BigInteger pendingNonce(Web3j web3j, String address) throws Exception {
        return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING)
                .send()
                .getTransactionCount();
    }

    private void record(String chain, String hash, String from, String to, String symbol, String contract,
                        BigDecimal amount, BigDecimal fee, long nonce, String status, String rawPayload) {
        repository.recordEvmTransaction(EvmTransactionRecord.builder()
                .chain(chain)
                .txHash(hash)
                .fromAddress(normalize(from))
                .toAddress(normalize(to))
                .assetSymbol(symbol)
                .contractAddress(contract)
                .amount(amount)
                .fee(fee)
                .nonce(nonce)
                .confirmations(0)
                .status(status)
                .rawPayload(rawPayload)
                .build());
    }
    private <T> T withWeb3(AccountChainProfile profile, Web3Request<T> request) {
        return rpcNodeService.withFailover(profile.getChain(), profile.getNetwork(), node -> {
            Web3j web3j = Web3j.build(new HttpService(node.getRpcUrl()));
            try {
                return request.apply(web3j);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                web3j.shutdown();
            }
        });
    }
    private AccountChainProfile profile(String chain) {
        return repository.findProfileByChain(chain)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + chain));
    }
    private String nativeSymbol(AccountChainProfile profile) {
        return profile.getNativeSymbol() == null ? profile.getChain() : profile.getNativeSymbol();
    }
    private BigDecimal fee(BigInteger gasPrice, BigInteger gasLimit) {
        return weiToNative(gasPrice.multiply(gasLimit));
    }
    private BigDecimal weiToNative(BigInteger wei) {
        return new BigDecimal(wei).divide(new BigDecimal(WEI_PER_NATIVE), 18, RoundingMode.DOWN);
    }
    private String normalize(String address) {
        return address == null ? null : address.toLowerCase(java.util.Locale.ROOT);
    }

    @FunctionalInterface
    private interface Web3Request<T> {
        T apply(Web3j web3j) throws Exception;
    }
}
