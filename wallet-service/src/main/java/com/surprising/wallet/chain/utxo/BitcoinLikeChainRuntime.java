package com.surprising.wallet.chain.utxo;

import com.alibaba.fastjson.JSONObject;
import com.googlecode.jsonrpc4j.JsonRpcClientException;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.BitcoinLikeChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainAsset;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.pojo.rpc.BtcLikeBlock;
import com.surprising.wallet.common.pojo.rpc.BtcLikeRawTransaction;
import com.surprising.wallet.common.pojo.rpc.ScriptPubKey;
import com.surprising.wallet.common.pojo.rpc.TxOutput;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashAddressCodec;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashNetworkParameters;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinNetworkParameters;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinNetworkParameters;
import com.surprising.wallet.chain.BitcoinLikeSettlementService;
import com.surprising.wallet.chain.ltc.LitecoinEsploraCommand;
import com.surprising.wallet.chain.rpc.BchCommand;
import com.surprising.wallet.chain.rpc.BtcCommand;
import com.surprising.wallet.chain.rpc.BtcLikeCommand;
import com.surprising.wallet.chain.rpc.DogeCommand;
import com.surprising.wallet.config.PubKeyConfig;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import com.surprising.wallet.wallet.service.AddressService;
import com.surprising.wallet.wallet.service.TransactionService;
import org.apache.commons.collections4.CollectionUtils;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.surprising.wallet.common.utils.Constants.UNSPENT_TX_ID;

/**
 * Bitcoin 类（UTXO 模型）链的通用运行时服务。
 *
 * <p>该类封装了 BTC、LTC、DOGE、BCH 四条 UTXO 链的共用逻辑，包括：
 * <ul>
 *   <li>存款地址生成（BIP44 派生、多签脚本类型区分 P2WSH/P2SH）</li>
 *   <li>地址格式校验（BCH 支持 CashAddr 和 Legacy 双向转换）</li>
 *   <li>区块交易扫描与 UTXO 持久化</li>
 *   <li>交易确认数刷新（充值确认、提现确认）</li>
 *   <li>余额汇总与签名交易广播</li>
 *   <li>防重组审计（通过 {@link #FINALITY_AUDIT_DEPTH} 深度回溯检查）</li>
 * </ul>
 *
 * <p>不同链通过 {@link #command(ChainType)} 获取对应的 RPC 命令执行器
 * （{@link com.surprising.wallet.chain.rpc.BtcCommand}、
 *  {@link com.surprising.wallet.chain.rpc.BchCommand}、
 *  {@link com.surprising.wallet.chain.rpc.DogeCommand}、
 *  {@link com.surprising.wallet.chain.ltc.LitecoinEsploraCommand}），
 * 通过 {@link #networkParameters(ChainType)} 获取对应的网络参数。
 *
 * <p>该类被各链的 {@link com.surprising.wallet.chain.BlockchainAdapter} 适配器
 * 委托调用，不直接面向外部。
 *
 * @see com.surprising.wallet.chain.btc.BtcChainAdapter
 * @see com.surprising.wallet.chain.ltc.LitecoinChainAdapter
 * @see com.surprising.wallet.chain.doge.DogeChainAdapter
 * @see com.surprising.wallet.chain.bch.BchChainAdapter
 */
@Service
public
class BitcoinLikeChainRuntime {

    /** 防重组审计深度：回溯最近 256 个区块检查是否发生链重组 */
    private static final int FINALITY_AUDIT_DEPTH = 256;

    /** 链配置数据库访问 */
    private final ChainJdbcRepository chainRepository;

    /** 多签公钥配置 */
    private final PubKeyConfig pubKeyConfig;

    /** 地址服务 */
    private final AddressService addressService;

    /** 交易服务提供者（延迟注入避免循环依赖） */
    private final ObjectProvider<TransactionService> transactionServiceProvider;

    /** UTXO 链结算服务 */
    private final BitcoinLikeSettlementService settlementService;

    /** BTC JSON-RPC 命令执行器 */
    private final BtcCommand btcCommand;

    /** LTC Esplora API 命令执行器 */
    private final LitecoinEsploraCommand ltcCommand;

    /** DOGE JSON-RPC 命令执行器 */
    private final DogeCommand dogeCommand;

    /** BCH JSON-RPC 命令执行器 */
    private final BchCommand bchCommand;

    /**
     * @param chainRepository             链配置数据库访问
     * @param pubKeyConfig                多签公钥配置
     * @param addressService              地址服务
     * @param transactionServiceProvider  交易服务提供者
     * @param settlementService           UTXO 链结算服务
     * @param btcCommand                  BTC RPC 命令
     * @param ltcCommand                  LTC RPC 命令
     * @param dogeCommand                 DOGE RPC 命令
     * @param bchCommand                  BCH RPC 命令
     */
    public BitcoinLikeChainRuntime(
            ChainJdbcRepository chainRepository,
            PubKeyConfig pubKeyConfig,
            AddressService addressService,
            ObjectProvider<TransactionService> transactionServiceProvider,
            BitcoinLikeSettlementService settlementService,
            BtcCommand btcCommand,
            LitecoinEsploraCommand ltcCommand,
            DogeCommand dogeCommand,
            BchCommand bchCommand) {
        this.chainRepository = chainRepository;
        this.pubKeyConfig = pubKeyConfig;
        this.addressService = addressService;
        this.transactionServiceProvider = transactionServiceProvider;
        this.settlementService = settlementService;
        this.btcCommand = btcCommand;
        this.ltcCommand = ltcCommand;
        this.dogeCommand = dogeCommand;
        this.bchCommand = bchCommand;
    }

    /**
     * 生成充值地址（自动递增派生索引）。
     *
     * <p>最多重试 5 次以避免索引冲突，禁止为默认热钱包用户（userId=0, biz=0）生成地址。
     *
     * @param chainType 链类型
     * @param userId    用户 ID
     * @param biz       业务标识
     * @return 新生成的地址对象
     * @throws DuplicateKeyException 如果 5 次尝试后仍遇到索引冲突
     */
    @Transactional(rollbackFor = Throwable.class)
    public synchronized Address generateDepositAddress(ChainType chainType, long userId, int biz) {
        rejectReservedHotAddress(userId, biz);
        AssetRuntimeMetadata asset = assetMetadata(chainType);
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                int index = nextAddressIndex(asset, userId, biz);
                Address address = buildAddress(chainType, userId, biz, index);
                chainRepository.upsertChainAddress(toChainAddressRecord(address, asset));
                return address;
            } catch (DuplicateKeyException e) {
                if (attempt == 4) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("failed to allocate a unique address index for " + chainType);
    }

    /**
     * 按指定派生索引生成充值地址。
     *
     * @param chainType  链类型
     * @param userId     用户 ID
     * @param biz        业务标识
     * @param childIndex BIP44 子索引（0 到 2147483647）
     * @return 指定索引的地址对象
     * @throws IllegalArgumentException 如果 childIndex 超出范围
     */
    @Transactional(rollbackFor = Throwable.class)
    public synchronized Address generateDepositAddressAtIndex(
            ChainType chainType, long userId, int biz, long childIndex) {
        rejectReservedHotAddress(userId, biz);
        if (childIndex < 0 || childIndex > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("childIndex must be between 0 and 2147483647");
        }
        AssetRuntimeMetadata asset = assetMetadata(chainType);
        Address address = buildAddress(chainType, userId, biz, Math.toIntExact(childIndex));
        chainRepository.upsertChainAddress(toChainAddressRecord(address, asset));
        return address;
    }
    /**
     * 校验链地址格式是否合法。
     *
     * <p>BCH 地址会先尝试 CashAddr 规范化，其他链直接通过 bitcoinj 解析校验。
     *
     * @param chainType 链类型
     * @param address   待校验的地址字符串
     * @return true 表示地址格式合法
     */
    public boolean checkAddress(ChainType chainType, String address) {
        if (!StringUtils.hasText(address)) {
            return false;
        }
        if (chainType == ChainType.BCH) {
            return normalizeBchAddress(address, networkParameters(chainType)) != null;
        }
        try {
            return org.bitcoinj.base.Address.fromString(networkParameters(chainType), address) != null;
        } catch (AddressFormatException e) {
            return false;
        }
    }
    /**
     * 获取充值所需确认数阈值。
     *
     * @param chainType 链类型
     * @return 充值确认数阈值
     */
    public long depositConfirmationThreshold(ChainType chainType) {
        return bitcoinLikeProfile(chainType).getDepositConfirmations();
    }
    /**
     * 获取提现所需确认数阈值。
     *
     * @param chainType 链类型
     * @return 提现确认数阈值
     */
    public long withdrawConfirmationThreshold(ChainType chainType) {
        return bitcoinLikeProfile(chainType).getWithdrawConfirmations();
    }
    /**
     * 获取粉尘阈值（原子单位）。
     *
     * <p>BCH 默认返回 {@link BitcoinCashFeePolicy#DUST_THRESHOLD_SAT}。
     *
     * @param chainType 链类型
     * @return 最小可花费金额（原子单位）
     */
    public long dustThresholdAtomic(ChainType chainType) {
        BitcoinLikeChainProfile profile = bitcoinLikeProfile(chainType);
        if (chainType == ChainType.BCH && profile.getDustThreshold() == null) {
            return BitcoinCashFeePolicy.DUST_THRESHOLD_SAT;
        }
        return profile.getDustThreshold() == null ? 0L : profile.getDustThreshold();
    }
    /**
     * 获取当前最佳区块高度。
     *
     * @param chainType 链类型
     * @return 最新区块高度
     */
    public long bestHeight(ChainType chainType) {
        return command(chainType).getBlockCount();
    }
    /**
     * 扫描指定高度的区块，查找与平台地址相关的交易。
     *
     * <p>对区块内的每笔交易并行提取 UTXO，过滤出属于平台地址的输出，
     * 持久化后返回交易 DTO 列表。同时将区块记录为规范链区块用于重组检测。
     *
     * @param chainType 链类型
     * @param height    区块高度
     * @return 相关交易列表，如果没有相关输出则返回空列表
     */
    public List<TransactionDTO> findRelatedTransactions(ChainType chainType, long height) {
        AssetRuntimeMetadata asset = assetMetadata(chainType);
        String hash = command(chainType).getBlockHash(height);
        BtcLikeBlock block = command(chainType).getBlock(hash);
        if (block == null) {
            return null;
        }
        chainRepository.observeCanonicalBlock(chainType.name(), "utxo-canonical", height,
                hash, block.getPreviousblockhash());
        long bestHeight = bestHeight(chainType);
        List<UtxoTransaction> results = block.getTx().parallelStream()
                .map(txid -> {
                    try {
                        updateWithdrawTransaction(chainType, txid, asset);
                        return getUtxos(chainType, txid, height, bestHeight, asset);
                    } catch (Throwable e) {
                        return List.<UtxoTransaction>of();
                    }
                })
                .filter(utxos -> !CollectionUtils.isEmpty(utxos))
                .collect(LinkedList::new, LinkedList::addAll, LinkedList::addAll);
        results.forEach(utxo -> utxo.setBlockHash(hash));
        if (CollectionUtils.isEmpty(results)) {
            return List.of();
        }
        persistScannedUtxos(chainType, results);
        return results.parallelStream().map(this::convertUtxoToDto).collect(Collectors.toList());
    }
    /**
     * 刷新所有待确认交易的确认数。
     *
     * <p>执行三个步骤：
     * <ol>
     *   <li>核对已入账的充值交易（防重组）</li>
     *   <li>刷新 UTXO 确认数（充值）</li>
     *   <li>刷新待确认提现交易的确认数</li>
     * </ol>
     *
     * @param chainType 链类型
     */
    public void updateTransactionConfirmations(ChainType chainType) {
        AssetRuntimeMetadata asset = assetMetadata(chainType);
        reconcileCreditedDeposits(chainType);
        updateUnifiedUtxoConfirmations(chainType, asset);
        updatePendingWithdrawConfirmations(chainType, asset);
    }
    private void reconcileCreditedDeposits(ChainType chainType) {
        long latest = bestHeight(chainType);
        long minimumHeight = Math.max(0L, latest - FINALITY_AUDIT_DEPTH + 1L);
        for (Long height : chainRepository.listCanonicalDepositBlockHeights(
                chainType.name(), minimumHeight)) {
            String hash = command(chainType).getBlockHash(height);
            BtcLikeBlock block = command(chainType).getBlock(hash);
            if (block == null) {
                throw new IllegalStateException("canonical UTXO block not found at height " + height);
            }
            var observation = chainRepository.observeCanonicalBlock(
                    chainType.name(), "utxo-canonical", height,
                    hash, block.getPreviousblockhash());
            if (observation.reorg()) {
                // The main cursor has already passed this height; ingest the replacement block now.
                findRelatedTransactions(chainType, height);
            }
        }
    }
    /**
     * 刷新该链所有地址的总余额。
     *
     * @param chainType 链类型
     */
    public void updateTotalBalance(ChainType chainType) {
        String chain = chainType.name();
        chainRepository.sumAvailableUtxoAmount(chain, chain);
    }
    /**
     * 广播已签名的提现交易到链上。
     *
     * <p>如果交易已存在（幂等检查），直接返回 txid 不再重复广播。
     * 广播失败后也会做一次幂等检查，避免因网络问题导致重复广播。
     *
     * @param chainType   链类型
     * @param transaction 包含签名数据的提现交易（rawTransaction 在 signature JSON 中）
     * @return 交易哈希（txid），广播失败返回空字符串
     */
    public String broadcastSignedTransaction(ChainType chainType, WithdrawTransaction transaction) {
        String expectedTxId = txId(transaction);
        try {
            JSONObject signature = JSONObject.parseObject(transaction.getSignature());
            String raw = signature.getString("rawTransaction");
            if (transactionExists(chainType, expectedTxId)) {
                return expectedTxId;
            }
            try {
                return command(chainType).sendRawTransaction(raw);
            } catch (Throwable broadcastError) {
                if (transactionExists(chainType, expectedTxId)) {
                    return expectedTxId;
                }
                throw broadcastError;
            }
        } catch (Throwable e) {
            return "";
        }
    }
    /**
     * 获取链资产的运行时元数据（包含链配置和资产信息）。
     *
     * @param chainType 链类型
     * @return 资产运行时元数据
     * @throws IllegalStateException 如果链配置或资产信息缺失
     */
    public AssetRuntimeMetadata assetMetadata(ChainType chainType) {
        AccountChainProfile profile = chainRepository.findProfileByChain(chainType.name())
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + chainType.name()));
        ChainAsset asset = chainRepository.findAsset(profile.getChain(), profile.getNativeSymbol()).orElse(null);
        return AssetRuntimeMetadata.fromProfile(profile, asset);
    }
    private int nextAddressIndex(AssetRuntimeMetadata asset, long userId, int biz) {
        return chainRepository.findMaxChainAddressIndex(
                        asset.chain(), asset.assetSymbol(), userId, biz, "DEPOSIT")
                .map(value -> Math.toIntExact(value + 1))
                .orElse(0);
    }
    private Address buildAddress(ChainType chainType, long userId, int biz, int index) {
        AssetRuntimeMetadata asset = assetMetadata(chainType);
        PubKeyConfig.AddressMetadata metadata;
        String address;
        if (chainType == ChainType.DOGE || chainType == ChainType.BCH) {
            metadata = pubKeyConfig.genLegacyThreeTwoAddressMetadata(
                    networkParameters(chainType), asset.getBip44CoinType(), Math.toIntExact(userId), biz, index);
            address = metadata.getAddress();
            if (chainType == ChainType.BCH) {
                BitcoinCashNetworkParameters params = (BitcoinCashNetworkParameters) networkParameters(chainType);
                address = BitcoinCashAddressCodec.fromLegacy(
                        LegacyAddress.fromBase58(params, metadata.getAddress()), params.cashPrefix());
            }
        } else {
            metadata = pubKeyConfig.genThreeTwoAddressMetadata(
                    networkParameters(chainType), asset.getBip44CoinType(), Math.toIntExact(userId), biz, index);
            address = metadata.getAddress();
        }
        return Address.builder()
                .address(address)
                .network(networkParameters(chainType).getPaymentProtocolId())
                .scriptType(chainType == ChainType.DOGE || chainType == ChainType.BCH ? "P2SH" : "P2WSH")
                .redeemScript(metadata.getRedeemScript())
                .witnessScript(chainType == ChainType.DOGE || chainType == ChainType.BCH ? "" : metadata.getWitnessScript())
                .derivationPath(metadata.getPath())
                .publicKeys(metadata.getPublicKeys())
                .biz(biz)
                .currency(asset.getName())
                .userId(userId)
                .index(index)
                .balance(BigDecimal.ZERO)
                .nonce(0)
                .status((byte) Constants.WAITING)
                .createDate(Date.from(Instant.now()))
                .updateDate(Date.from(Instant.now()))
                .build();
    }
    private ChainAddressRecord toChainAddressRecord(Address address, AssetRuntimeMetadata asset) {
        return ChainAddressRecord.builder()
                .chain(asset.chain())
                .assetSymbol(asset.assetSymbol())
                .accountId(address.getUserId().toString())
                .userId(address.getUserId())
                .biz(address.getBiz())
                .addressIndex(address.getIndex().longValue())
                .address(address.getAddress())
                .ownerAddress(null)
                .derivationPath(address.getDerivationPath())
                .walletRole("DEPOSIT")
                .enabled(true)
                .build();
    }

    private List<UtxoTransaction> getUtxos(
            ChainType chainType, String txid, long height, long bestHeight, AssetRuntimeMetadata asset) {
        BtcLikeRawTransaction rawTransaction = getRawTransaction(chainType, txid);
        if (rawTransaction == null || CollectionUtils.isEmpty(rawTransaction.getVout())) {
            return List.of();
        }
        return rawTransaction.getVout().parallelStream()
                .map(output -> toUtxo(chainType, output, txid, height, bestHeight, asset))
                .filter(utxo -> !ObjectUtils.isEmpty(utxo))
                .collect(Collectors.toList());
    }

    private UtxoTransaction toUtxo(
            ChainType chainType, TxOutput output, String txid, long height, long bestHeight, AssetRuntimeMetadata asset) {
        ScriptPubKey pubKey = output.getScriptPubKey();
        String addressText = normalizeScannedAddress(chainType, extractOutputAddress(chainType, pubKey));
        if (!StringUtils.hasText(addressText)) {
            return null;
        }
        Address address = addressService.getAddress(addressText, asset);
        if (ObjectUtils.isEmpty(address)) {
            return null;
        }
        long confirmations = bestHeight >= height ? bestHeight - height + 1L : 0L;
        return UtxoTransaction.builder()
                .balance(output.getValue())
                .address(addressText)
                .biz(address.getBiz())
                .currency(asset.getIndex())
                .spent((byte) 0)
                .spentTxId(UNSPENT_TX_ID)
                .seq(Integer.valueOf(output.getN()).shortValue())
                .txId(txid)
                .blockHeight(height)
                .confirmNum(confirmations)
                .status((byte) Constants.WAITING)
                .credited(false)
                .createDate(Date.from(Instant.now()))
                .updateDate(Date.from(Instant.now()))
                .build();
    }
    private String extractOutputAddress(ChainType chainType, ScriptPubKey pubKey) {
        if (pubKey == null) {
            return null;
        }
        if (chainType == ChainType.BCH && !CollectionUtils.isEmpty(pubKey.getCashAddrs())) {
            return pubKey.getCashAddrs().get(0);
        }
        if (StringUtils.hasText(pubKey.getAddress())) {
            return pubKey.getAddress();
        }
        if (!CollectionUtils.isEmpty(pubKey.getAddresses())) {
            return pubKey.getAddresses().get(0);
        }
        return null;
    }
    private String normalizeScannedAddress(ChainType chainType, String address) {
        if (chainType != ChainType.BCH) {
            return address;
        }
        return normalizeBchAddress(address, networkParameters(chainType));
    }
    private String normalizeBchAddress(String address, NetworkParameters params) {
        if (address == null || address.isBlank()) {
            return null;
        }
        BitcoinCashNetworkParameters bchParams = (BitcoinCashNetworkParameters) params;
        String candidate = address.trim();
        try {
            BitcoinCashAddressCodec.Decoded decoded =
                    BitcoinCashAddressCodec.decode(bchParams.cashPrefix(), candidate);
            return BitcoinCashAddressCodec.encode(bchParams.cashPrefix(), decoded.type(), decoded.hash());
        } catch (IllegalArgumentException ignored) {
        }
        try {
            return BitcoinCashAddressCodec.fromLegacy(
                    LegacyAddress.fromBase58(bchParams, candidate), bchParams.cashPrefix());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
    private BtcLikeRawTransaction getRawTransaction(ChainType chainType, String txid) {
        try {
            return command(chainType).getRawTransaction(txid, true);
        } catch (JsonRpcClientException e) {
            try {
                return command(chainType).getRawTransaction(txid, 1);
            } catch (Throwable retryError) {
                return null;
            }
        } catch (Throwable e) {
            return null;
        }
    }
    private Integer confirmations(ChainType chainType, String txId) {
        BtcLikeRawTransaction transaction = getRawTransaction(chainType, txId);
        return transaction == null ? 0 : transaction.getConfirmations();
    }
    private void updateWithdrawTransaction(ChainType chainType, String txId, AssetRuntimeMetadata asset) {
        Optional<WithdrawTransaction> existing =
                chainRepository.findBitcoinLikeSigningTransactionByTxId(asset, txId);
        if (existing.isEmpty()) {
            return;
        }
        WithdrawTransaction transaction = existing.get();
        if (transaction.getStatus() != null && transaction.getStatus() >= Constants.CONFIRM) {
            return;
        }
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());
        int confirmations = confirmations(chainType, txId);
        if (confirmations < withdrawConfirmationThreshold(chainType)) {
            markConfirming(chainType, signature, txId);
            return;
        }
        settlementService.settleConfirmed(transaction, txId, asset);
    }
    private void updateUnifiedUtxoConfirmations(ChainType chainType, AssetRuntimeMetadata asset) {
        int pageSize = 500;
        int offset = 0;
        String chain = chainType.name();
        while (true) {
            List<UtxoTransaction> utxos = chainRepository.listAvailableUtxosBelowConfirmations(
                    chain, chain, asset.getConfirmNum(), pageSize, offset);
            for (UtxoTransaction utxo : utxos) {
                int confirmations = Math.max(confirmations(chainType, utxo.getTxId()), 0);
                utxo.setConfirmNum((long) confirmations);
                chainRepository.updateUtxoConfirmations(chain, utxo.getTxId(), utxo.getSeq(), confirmations);
                if (chainRepository.depositRecordExists(chain, utxo.getTxId(), utxo.getSeq())
                        && enrichUtxoMetadata(utxo, asset)) {
                    transactionServiceProvider.getObject().saveTransaction(convertUtxoToDto(utxo));
                }
            }
            if (utxos.size() < pageSize) {
                break;
            }
            offset += pageSize;
        }
    }
    private void updatePendingWithdrawConfirmations(ChainType chainType, AssetRuntimeMetadata asset) {
        List<WithdrawTransaction> pending = chainRepository.findSentBitcoinLikeSigningTransactions(asset);
        for (WithdrawTransaction transaction : pending) {
            if (!StringUtils.hasText(transaction.getTxId())) {
                continue;
            }
            int confirmations = confirmations(chainType, transaction.getTxId());
            if (confirmations >= withdrawConfirmationThreshold(chainType)) {
                updateWithdrawTransaction(chainType, transaction.getTxId(), asset);
            } else if (confirmations > 0) {
                markConfirming(chainType, JSONObject.parseObject(transaction.getSignature()), transaction.getTxId());
            }
        }
    }
    private void markConfirming(ChainType chainType, JSONObject signature, String txId) {
        String chain = chainType.name();
        List<WithdrawRecord> records = signature.getJSONArray("withdraw").toJavaList(WithdrawRecord.class);
        records.forEach(record -> {
            java.util.UUID tenantId = chainRepository.requireWithdrawalTenant(
                    chain, record.getWithdrawId());
            chainRepository.updateWithdrawalStatus(
                    tenantId, chain, record.getWithdrawId(), "CONFIRMING", null, txId, null);
        });
    }
    private boolean enrichUtxoMetadata(UtxoTransaction utxo, AssetRuntimeMetadata asset) {
        Address address = addressService.getAddress(utxo.getAddress(), asset);
        if (address == null) {
            return false;
        }
        utxo.setBiz(address.getBiz());
        utxo.setCurrency(asset.getIndex());
        return true;
    }
    private void persistScannedUtxos(ChainType chainType, List<UtxoTransaction> utxos) {
        String chain = chainType.name();
        for (UtxoTransaction utxo : utxos) {
            chainRepository.upsertUtxo(
                    chain,
                    chain,
                    utxo.getTxId(),
                    utxo.getSeq(),
                    utxo.getAddress(),
                    utxo.getBalance(),
                    utxo.getBlockHeight(),
                    utxo.getBlockHash(),
                    utxo.getConfirmNum().intValue(),
                    Boolean.TRUE.equals(utxo.getCredited()));
        }
    }
    private TransactionDTO convertUtxoToDto(UtxoTransaction utxo) {
        return TransactionDTO.builder()
                .address(utxo.getAddress())
                .balance(utxo.getBalance())
                .blockHeight(utxo.getBlockHeight())
                .blockHash(utxo.getBlockHash())
                .confirmNum(utxo.getConfirmNum())
                .txId(utxo.getTxId() + "-" + utxo.getSeq())
                .currency(utxo.getCurrency())
                .biz(utxo.getBiz())
                .build();
    }
    private boolean transactionExists(ChainType chainType, String txId) {
        try {
            return getRawTransaction(chainType, txId) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
    private String txId(WithdrawTransaction transaction) {
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());
        String raw = signature.getString("rawTransaction");
        Transaction signCompleteTx = Transaction.read(java.nio.ByteBuffer.wrap(java.util.HexFormat.of().parseHex(raw)));
        return signCompleteTx.getTxId().toString();
    }
    private void rejectReservedHotAddress(long userId, int biz) {
        if (HotWalletRules.isDefaultHotUser(userId, biz)) {
            throw new IllegalArgumentException("userId=0,biz=0 is reserved for the unique default hot wallet address");
        }
    }
    private BitcoinLikeChainProfile bitcoinLikeProfile(ChainType chainType) {
        AccountChainProfile enabled = chainRepository.findProfileByChain(chainType.name())
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + chainType.name()));
        return chainRepository.findBitcoinLikeProfile(chainType.name(), enabled.getNetwork())
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for " + chainType.name() + "/" + enabled.getNetwork()));
    }
    private NetworkParameters networkParameters(ChainType chainType) {
        String network = bitcoinLikeProfile(chainType).getNetwork();
        boolean mainnet = "main".equalsIgnoreCase(network) || "mainnet".equalsIgnoreCase(network);
        boolean regtest = "regtest".equalsIgnoreCase(network);
        return switch (chainType) {
            case BTC -> mainnet ? MainNetParams.get() : (regtest ? RegTestParams.get() : TestNet3Params.get());
            case LTC -> {
                if (regtest) {
                    throw new IllegalStateException("LTC regtest network parameters are not implemented");
                }
                yield mainnet ? LitecoinNetworkParameters.mainnet() : LitecoinNetworkParameters.testnet();
            }
            case DOGE -> mainnet ? DogecoinNetworkParameters.mainnet()
                    : (regtest ? DogecoinNetworkParameters.regtest() : DogecoinNetworkParameters.testnet());
            case BCH -> mainnet ? BitcoinCashNetworkParameters.mainnet()
                    : (regtest ? BitcoinCashNetworkParameters.regtest() : BitcoinCashNetworkParameters.testnet());
            default -> throw new IllegalStateException("unsupported bitcoin-like chain " + chainType);
        };
    }
    private BtcLikeCommand command(ChainType chainType) {
        return switch (chainType) {
            case BTC -> btcCommand;
            case LTC -> ltcCommand;
            case DOGE -> dogeCommand;
            case BCH -> bchCommand;
            default -> throw new IllegalStateException("unsupported bitcoin-like chain " + chainType);
        };
    }
}
