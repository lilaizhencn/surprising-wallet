package com.surprising.wallet.chain.utxo;

import com.googlecode.jsonrpc4j.JsonRpcClientException;
import com.surprising.wallet.chain.ltc.LitecoinEsploraCommand;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.chain.model.HotWalletRules;
import com.surprising.wallet.chain.model.UtxoBlock;
import com.surprising.wallet.chain.rpc.BchCommand;
import com.surprising.wallet.chain.rpc.BtcCommand;
import com.surprising.wallet.chain.rpc.BtcLikeCommand;
import com.surprising.wallet.chain.rpc.DogeCommand;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.UtxoOutput;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.signing.UtxoSignedTransaction;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.config.PubKeyConfig;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashAddressCodec;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashNetworkParameters;
import com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork;
import com.surprising.wallet.sdk.bitcoinj.rpc.model.BtcLikeBlock;
import com.surprising.wallet.sdk.bitcoinj.rpc.model.BtcLikeRawTransaction;
import com.surprising.wallet.sdk.bitcoinj.rpc.model.ScriptPubKey;
import com.surprising.wallet.sdk.bitcoinj.rpc.model.TxOutput;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;


/** Bitcoin-like 地址派生、地址校验、完整区块/交易观察和签名广播；账务由应用用例编排。 */
@Service
public class BitcoinLikeChainRuntime {

    private final ChainProfileRepository chainProfileRepository;
    private final ChainAssetRepository chainAssetRepository;
    private final ChainAddressRepository addresses;
    private final PubKeyConfig pubKeyConfig;

    /** BTC JSON-RPC 命令执行器 */
    private final BtcCommand btcCommand;

    /** LTC Esplora API 命令执行器 */
    private final LitecoinEsploraCommand ltcCommand;

    /** DOGE JSON-RPC 命令执行器 */
    private final DogeCommand dogeCommand;

    /** BCH JSON-RPC 命令执行器 */
    private final BchCommand bchCommand;

    public BitcoinLikeChainRuntime(
            ChainProfileRepository chainProfileRepository,
            ChainAssetRepository chainAssetRepository,
            PubKeyConfig pubKeyConfig,
            BtcCommand btcCommand,
            LitecoinEsploraCommand ltcCommand,
            DogeCommand dogeCommand,
            BchCommand bchCommand,
            ChainAddressRepository addresses) {
        this.chainProfileRepository = java.util.Objects.requireNonNull(chainProfileRepository);
        this.chainAssetRepository = java.util.Objects.requireNonNull(chainAssetRepository);
        this.pubKeyConfig = java.util.Objects.requireNonNull(pubKeyConfig);
        this.btcCommand = java.util.Objects.requireNonNull(btcCommand);
        this.ltcCommand = java.util.Objects.requireNonNull(ltcCommand);
        this.dogeCommand = java.util.Objects.requireNonNull(dogeCommand);
        this.bchCommand = java.util.Objects.requireNonNull(bchCommand);
        this.addresses = java.util.Objects.requireNonNull(addresses);
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
                addresses.upsert(toChainAddressRecord(address, asset));
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
        addresses.upsert(toChainAddressRecord(address, asset));
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
        AccountChainProfile profile = bitcoinLikeProfile(chainType);
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

    public String blockHash(ChainType chainType, long height) {
        return command(chainType).getBlockHash(height);
    }
    /** 读取完整区块后才返回事实；任何交易读取失败都不能当作空区块推进。 */
    public java.util.Optional<UtxoBlock> observeBlock(ChainType chainType, long height) {
        String hash = command(chainType).getBlockHash(height);
        BtcLikeBlock block = command(chainType).getBlock(hash);
        if (block == null) return java.util.Optional.empty();
        long best = bestHeight(chainType);
        var tracked = new java.util.HashSet<>(addresses.listEnabledAddresses(chainType.name()));
        var params = networkParameters(chainType);
        var outputs = new java.util.ArrayList<UtxoOutput>();
        for (String txId : block.getTx()) {
            var transaction = getRawTransaction(chainType, txId);
            if (transaction == null || transaction.getVout() == null)
                throw new IllegalStateException("UTXO transaction unavailable: " + txId);
            for (TxOutput output : transaction.getVout()) {
                String address = normalizeScannedAddress(chainType,
                        extractOutputAddress(chainType, output.getScriptPubKey()), params);
                if (address == null || !tracked.contains(address)) continue;
                outputs.add(new UtxoOutput(txId, output.getN(), address, output.getValue(), height, hash,
                        Math.toIntExact(Math.max(0, best - height + 1))));
            }
        }
        return java.util.Optional.of(new UtxoBlock(height, hash, block.getPreviousblockhash(), outputs));
    }

    /**
     * 广播已签名的提现交易到链上。
     *
     * <p>如果交易已存在（幂等检查），直接返回 txid 不再重复广播。
     * 广播失败后也会做一次幂等检查，避免因网络问题导致重复广播。
     *
     * @param chainType   链类型
     * @param transaction 已签名交易、哈希和最小单位手续费
     * @return 交易哈希（txid），广播失败返回空字符串
     */
    public String broadcastSignedTransaction(ChainType chainType, UtxoSignedTransaction transaction) {
        String expectedTxId = Transaction.read(java.nio.ByteBuffer.wrap(
                java.util.HexFormat.of().parseHex(transaction.transactionHex()))).getTxId().toString();
        if (!expectedTxId.equals(transaction.txId())) throw new IllegalArgumentException("signed transaction hash mismatch");
        try {
            String raw = transaction.transactionHex();
            if (transactionExists(chainType, expectedTxId)) {
                return expectedTxId;
            }
            try {
                String hash = command(chainType).sendRawTransaction(raw);
                if (!expectedTxId.equals(hash)) throw new IllegalStateException("RPC transaction hash mismatch");
                return expectedTxId;
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
        AccountChainProfile profile = chainProfileRepository.findAccountByChain(chainType.name())
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + chainType.name()));
        ChainAsset asset = chainAssetRepository.findActiveDefinition(profile.getChain(), profile.getNativeSymbol()).orElse(null);
        return AssetRuntimeMetadata.fromProfile(
                profile.getRuntimeCurrencyId(),
                profile.getChain(),
                profile.getNativeSymbol(),
                profile.getDepositConfirmations(),
                profile.getBip44CoinType(),
                asset == null ? null : asset.getDecimals(),
                asset == null ? null : asset.getContractAddress());
    }
    private int nextAddressIndex(AssetRuntimeMetadata asset, long userId, int biz) {
        return addresses.findMaxIndex(asset.chain(), asset.assetSymbol(), userId, biz, "DEPOSIT")
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
            address = metadata.address();
            if (chainType == ChainType.BCH) {
                BitcoinCashNetworkParameters params = (BitcoinCashNetworkParameters) networkParameters(chainType);
                address = BitcoinCashAddressCodec.fromLegacy(
                        LegacyAddress.fromBase58(params, metadata.address()), params.cashPrefix());
            }
        } else {
            metadata = pubKeyConfig.genThreeTwoAddressMetadata(
                    networkParameters(chainType), asset.getBip44CoinType(), Math.toIntExact(userId), biz, index);
            address = metadata.address();
        }
        return Address.builder()
                .address(address)
                .network(networkParameters(chainType).getPaymentProtocolId())
                .scriptType(chainType == ChainType.DOGE || chainType == ChainType.BCH ? "P2SH" : "P2WSH")
                .redeemScript(metadata.redeemScript())
                .witnessScript(chainType == ChainType.DOGE || chainType == ChainType.BCH ? "" : metadata.witnessScript())
                .derivationPath(metadata.path())
                .publicKeys(metadata.publicKeys())
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

    private String extractOutputAddress(ChainType chainType, ScriptPubKey pubKey) {
        if (pubKey == null) {
            return null;
        }
        if (chainType == ChainType.BCH && pubKey.getCashAddrs() != null && !pubKey.getCashAddrs().isEmpty()) {
            return pubKey.getCashAddrs().get(0);
        }
        if (StringUtils.hasText(pubKey.getAddress())) {
            return pubKey.getAddress();
        }
        if (pubKey.getAddresses() != null && !pubKey.getAddresses().isEmpty()) {
            return pubKey.getAddresses().get(0);
        }
        return null;
    }
    private String normalizeScannedAddress(ChainType chainType, String address, NetworkParameters params) {
        if (chainType != ChainType.BCH) {
            return address;
        }
        return normalizeBchAddress(address, params);
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
    public int confirmations(ChainType chainType, String txId) {
        BtcLikeRawTransaction transaction = getRawTransaction(chainType, txId);
        return transaction == null ? 0 : Math.max(0, transaction.getConfirmations());
    }
    private boolean transactionExists(ChainType chainType, String txId) {
        try {
            return getRawTransaction(chainType, txId) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
    private void rejectReservedHotAddress(long userId, int biz) {
        if (HotWalletRules.isDefaultHotUser(userId, biz)) {
            throw new IllegalArgumentException("userId=0,biz=0 is reserved for the unique default hot wallet address");
        }
    }
    private AccountChainProfile bitcoinLikeProfile(ChainType chainType) {
        return chainProfileRepository.findAccountByChain(chainType.name())
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + chainType.name()));
    }
    private NetworkParameters networkParameters(ChainType chainType) {
        return BitcoinLikeNetwork.parse(bitcoinLikeProfile(chainType).getNetwork()).parameters(chainType.name());
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
