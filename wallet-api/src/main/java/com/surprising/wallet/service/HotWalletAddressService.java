package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.chain.model.HotWalletRules;
import com.surprising.wallet.sdk.ed25519.Ed25519DerivedKey;
import com.surprising.wallet.chain.aptos.AptosKeyService;
import com.surprising.wallet.chain.cardano.CardanoKeyService;
import com.surprising.wallet.chain.monero.MoneroAddressService;
import com.surprising.wallet.chain.near.NearKeyService;
import com.surprising.wallet.chain.polkadot.PolkadotKeyService;
import com.surprising.wallet.chain.solana.SolanaKeyService;
import com.surprising.wallet.chain.starknet.StarknetKeyService;
import com.surprising.wallet.chain.sui.SuiKeyService;
import com.surprising.wallet.chain.ton.TonKeyService;
import com.surprising.wallet.chain.xrp.XrpKeyService;
import com.surprising.wallet.config.PubKeyConfig;
import com.surprising.wallet.repository.ChainJdbcRepository;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashAddressCodec;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashNetworkParameters;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinNetworkParameters;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinNetworkParameters;
import lombok.RequiredArgsConstructor;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.p2p.solanaj.core.PublicKey;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.tron.TronWalletApi;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 热钱包地址派生服务，负责跨链的 BIP44/Ed25519 地址派生。
 *
 * <p>支持 Bitcoin-like、EVM、TRON、Solana、Sui、Aptos、TON、XRP、Cardano、NEAR、
 * Polkadot、HyperCore、Monero 和 Starknet。
 *
 * <p>默认热钱包地址（userId=0, biz=0, index=0）用于归集和提现手续费支付，
 * 启动时会校验数据库中地址与派生结果的一致性。
 */
@Service
@RequiredArgsConstructor
public
class HotWalletAddressService {

    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository repository;
    /** 多签公钥配置（用于 BTC-like 链地址派生） */
    private final PubKeyConfig pubKeyConfig;
    /**
     * 保存 {@code solanaKeyService}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final SolanaKeyService solanaKeyService;
    /**
     * 保存 {@code suiKeyService}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final SuiKeyService suiKeyService;
    /**
     * 保存 {@code aptosKeyService}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final AptosKeyService aptosKeyService;
    /**
     * 保存 {@code tonKeyService}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final TonKeyService tonKeyService;
    /**
     * 保存 {@code xrpKeyService}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final XrpKeyService xrpKeyService;
    /**
     * 保存 {@code cardanoKeyService}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final CardanoKeyService cardanoKeyService;
    /**
     * 保存 {@code nearKeyService}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final NearKeyService nearKeyService;
    /**
     * 保存 {@code polkadotKeyService}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final PolkadotKeyService polkadotKeyService;
    /**
     * 保存 {@code moneroAddressService}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final MoneroAddressService moneroAddressService;
    /** Starknet 合约账户地址派生服务。 */
    private final StarknetKeyService starknetKeyService;

    /**
     * 查找默认热钱包地址（userId=0, biz=0, index=0）。
     *
     * @param chain       链名称
     * @param assetSymbol 资产符号
     * @return 地址记录（可能为 Optional.empty()）
     */
    public Optional<ChainAddressRecord> findDefaultHotAddress(String chain, String assetSymbol) {
        return repository.findChainAddress(
                chain,
                assetSymbol,
                HotWalletRules.DEFAULT_HOT_USER_ID,
                HotWalletRules.DEFAULT_HOT_BIZ,
                HotWalletRules.DEFAULT_HOT_ADDRESS_INDEX,
                HotWalletRules.DEFAULT_HOT_WALLET_ROLE);
    }
    /**
     * 校验 {@code requireVerifiedDefaultHotAddress} 对应的前置条件，不满足时抛出明确异常。
     */
    public ChainAddressRecord requireVerifiedDefaultHotAddress(AccountChainProfile profile) {
        ChainAddressRecord expected = deriveDefaultHotAddress(profile);
        return requireDefaultHotAddressMatches(profile, expected);
    }
    /**
     * 校验 {@code requireDefaultHotAddressMatches} 对应的前置条件，不满足时抛出明确异常。
     */
    private ChainAddressRecord requireDefaultHotAddressMatches(AccountChainProfile profile, ChainAddressRecord expected) {
        ChainAddressRecord actual = findDefaultHotAddress(profile.getChain(), profile.getNativeSymbol())
                .orElseThrow(() -> new IllegalStateException("missing default hot wallet chain_address for "
                        + profile.getChain() + "/" + profile.getNativeSymbol()
                        + " userId=0 biz=0 index=0 walletRole=DEPOSIT"));

        if (!Boolean.TRUE.equals(actual.getEnabled())) {
            throw new IllegalStateException("default hot wallet chain_address is disabled for "
                    + profile.getChain() + "/" + profile.getNativeSymbol());
        }
        if (!sameAddress(profile, expected.getAddress(), actual.getAddress())) {
            throw new IllegalStateException("default hot wallet address mismatch for "
                    + profile.getChain() + "/" + profile.getNativeSymbol()
                    + " expected=" + expected.getAddress()
                    + " actual=" + actual.getAddress());
        }
        if (StringUtils.hasText(expected.getDerivationPath())
                && StringUtils.hasText(actual.getDerivationPath())
                && !expected.getDerivationPath().equals(actual.getDerivationPath())) {
            throw new IllegalStateException("default hot wallet derivation path mismatch for "
                    + profile.getChain() + "/" + profile.getNativeSymbol()
                    + " expected=" + expected.getDerivationPath()
                    + " actual=" + actual.getDerivationPath());
        }

        List<ChainAddressRecord> reservedRows = repository.listReservedHotNamespaceAddresses(profile.getChain());
        List<ChainAddressRecord> extra = reservedRows.stream()
                .filter(record -> !isDefaultHotAddressRow(profile, record))
                .toList();
        if (!extra.isEmpty()) {
            String ids = extra.stream()
                    .map(record -> String.valueOf(record.getId()))
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
            throw new IllegalStateException("extra default-hot namespace addresses are forbidden for "
                    + profile.getChain()
                    + "; delete chain_address ids=" + ids);
        }
        return actual;
    }
    /**
     * 构建或生成 {@code deriveDefaultHotAddress} 对应的结果，并执行输入和状态校验。
     */
    public ChainAddressRecord deriveDefaultHotAddress(AccountChainProfile profile) {
        return deriveAddress(
                profile,
                HotWalletRules.DEFAULT_HOT_USER_ID,
                HotWalletRules.DEFAULT_HOT_BIZ,
                HotWalletRules.DEFAULT_HOT_ADDRESS_INDEX,
                HotWalletRules.DEFAULT_HOT_WALLET_ROLE);
    }

    /**
     * 构建或生成 {@code deriveAddress} 对应的结果，并执行输入和状态校验。
     */
    public ChainAddressRecord deriveAddress(AccountChainProfile profile, long userId, int biz,
                                            long addressIndex, String walletRole) {
        String family = normalize(profile.getFamily());
        return switch (family) {
            case "bitcoin-like" -> deriveBitcoinLike(profile, userId, biz, addressIndex, walletRole);
            case "evm" -> deriveSecp256k1(profile, userId, biz, addressIndex, walletRole, AddressFormat.EVM);
            case "hypercore" -> deriveSecp256k1(profile, userId, biz, addressIndex, walletRole, AddressFormat.EVM);
            case "tron" -> deriveSecp256k1(profile, userId, biz, addressIndex, walletRole, AddressFormat.TRON);
            case "solana" -> deriveSolana(profile, userId, biz, addressIndex, walletRole);
            case "sui" -> deriveSui(profile, userId, biz, addressIndex, walletRole);
            case "aptos" -> deriveAptos(profile, userId, biz, addressIndex, walletRole);
            case "ton" -> deriveTon(profile, userId, biz, addressIndex, walletRole);
            case "xrp" -> deriveXrp(profile, userId, biz, addressIndex, walletRole);
            case "cardano" -> deriveCardano(profile, userId, biz, addressIndex, walletRole);
            case "near" -> deriveNear(profile, userId, biz, addressIndex, walletRole);
            case "polkadot" -> derivePolkadot(profile, userId, biz, addressIndex, walletRole);
            case "monero" -> deriveMonero(userId, biz, addressIndex, walletRole);
            case "starknet" -> deriveStarknet(profile, userId, biz, addressIndex, walletRole);
            default -> throw new IllegalStateException("unsupported hot wallet derivation family: "
                    + profile.getChain() + "/" + profile.getFamily());
        };
    }

    /** 派生 Starknet counterfactual 账户地址。 */
    private ChainAddressRecord deriveStarknet(AccountChainProfile profile, long userId, int biz,
                                              long addressIndex, String walletRole) {
        return starknetKeyService.derive(profile, userId, biz, addressIndex)
                .toAddressRecord(profile, userId, biz, addressIndex, walletRole);
    }

    /**
     * 构建或生成 {@code deriveBitcoinLike} 对应的结果，并执行输入和状态校验。
     */
    private ChainAddressRecord deriveBitcoinLike(AccountChainProfile profile, long userId, int biz,
                                                 long addressIndex, String walletRole) {
        NetworkParameters params = bitcoinLikeNetworkParameters(profile);
        int index = Math.toIntExact(addressIndex);
        int user = Math.toIntExact(userId);
        PubKeyConfig.AddressMetadata metadata;
        String address;
        String chain = normalize(profile.getChain());
        if ("doge".equals(chain) || "bch".equals(chain)) {
            metadata = pubKeyConfig.genLegacyThreeTwoAddressMetadata(
                    params, profile.getBip44CoinType(), user, biz, index);
            address = metadata.getAddress();
            if ("bch".equals(chain)) {
                BitcoinCashNetworkParameters bchParams = (BitcoinCashNetworkParameters) params;
                address = BitcoinCashAddressCodec.fromLegacy(
                        LegacyAddress.fromBase58(bchParams, metadata.getAddress()), bchParams.cashPrefix());
            }
        } else {
            metadata = pubKeyConfig.genThreeTwoAddressMetadata(
                    params, profile.getBip44CoinType(), user, biz, index);
            address = metadata.getAddress();
        }
        return baseRecord(profile, userId, biz, addressIndex, address, null, metadata.getPath(), walletRole);
    }
    /**
     * 执行 {@code bitcoinLikeNetworkParameters} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private NetworkParameters bitcoinLikeNetworkParameters(AccountChainProfile profile) {
        String chain = normalize(profile.getChain());
        String network = normalize(profile.getNetwork());
        return switch (chain) {
            case "btc" -> {
                if (isMainnet(network)) {
                    yield MainNetParams.get();
                }
                yield "regtest".equals(network) ? RegTestParams.get() : TestNet3Params.get();
            }
            case "ltc" -> {
                if ("regtest".equals(network)) {
                    throw new IllegalStateException("LTC regtest network parameters are not implemented");
                }
                yield isMainnet(network) ? LitecoinNetworkParameters.mainnet() : LitecoinNetworkParameters.testnet();
            }
            case "doge" -> {
                if (isMainnet(network)) {
                    yield DogecoinNetworkParameters.mainnet();
                }
                yield "regtest".equals(network)
                        ? DogecoinNetworkParameters.regtest()
                        : DogecoinNetworkParameters.testnet();
            }
            case "bch" -> {
                if (isMainnet(network)) {
                    yield BitcoinCashNetworkParameters.mainnet();
                }
                yield "regtest".equals(network)
                        ? BitcoinCashNetworkParameters.regtest()
                        : BitcoinCashNetworkParameters.testnet();
            }
            default -> throw new IllegalStateException("unsupported bitcoin-like chain for public key validation: "
                    + profile.getChain());
        };
    }
    /**
     * 判断 {@code isMainnet} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private boolean isMainnet(String network) {
        return "main".equals(network) || "mainnet".equals(network);
    }

    /**
     * 构建或生成 {@code deriveSecp256k1} 对应的结果，并执行输入和状态校验。
     */
    private ChainAddressRecord deriveSecp256k1(AccountChainProfile profile, long userId, int biz,
                                               long addressIndex, String walletRole, AddressFormat format) {
        ECKey ecKey = pubKeyConfig.node2().getChild(44)
                .getChild(derivationCoinType(profile))
                .getChild(biz)
                .getChild(Math.toIntExact(userId))
                .getChild(Math.toIntExact(addressIndex))
                .getEcKey();
        String address = switch (format) {
            case EVM -> evmAddress(ecKey);
            case TRON -> TronWalletApi.getAddress(ecKey.getPubKey());
            case XRP -> XrpKeyService.address(ecKey);
        };
        return baseRecord(profile, userId, biz, addressIndex, address, address,
                derivationPath(profile, userId, biz, addressIndex), walletRole);
    }

    /**
     * 执行 {@code evmAddress} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static String evmAddress(ECKey ecKey) {
        return "0x" + Keys.getAddress(Sign.publicFromPoint(ecKey.decompress().getPubKey()));
    }

    /**
     * 构建或生成 {@code deriveSolana} 对应的结果，并执行输入和状态校验。
     */
    private ChainAddressRecord deriveSolana(AccountChainProfile profile, long userId, int biz,
                                            long addressIndex, String walletRole) {
        Ed25519DerivedKey key = solanaKeyService.derive(userId, biz, addressIndex);
        String address = new PublicKey(key.publicKey()).toBase58();
        return baseRecord(profile, userId, biz, addressIndex, address, address, key.derivationPath(), walletRole);
    }

    /**
     * 构建或生成 {@code deriveSui} 对应的结果，并执行输入和状态校验。
     */
    private ChainAddressRecord deriveSui(AccountChainProfile profile, long userId, int biz,
                                         long addressIndex, String walletRole) {
        Ed25519DerivedKey key = suiKeyService.derive(userId, biz, addressIndex);
        String address = SuiKeyService.address(key.publicKey());
        return baseRecord(profile, userId, biz, addressIndex, address, address, key.derivationPath(), walletRole);
    }

    /**
     * 构建或生成 {@code deriveAptos} 对应的结果，并执行输入和状态校验。
     */
    private ChainAddressRecord deriveAptos(AccountChainProfile profile, long userId, int biz,
                                           long addressIndex, String walletRole) {
        Ed25519DerivedKey key = aptosKeyService.derive(userId, biz, addressIndex);
        String address = AptosKeyService.address(key.publicKey());
        return baseRecord(profile, userId, biz, addressIndex, address, address, key.derivationPath(), walletRole);
    }

    /**
     * 构建或生成 {@code deriveTon} 对应的结果，并执行输入和状态校验。
     */
    private ChainAddressRecord deriveTon(AccountChainProfile profile, long userId, int biz,
                                         long addressIndex, String walletRole) {
        Ed25519DerivedKey key = tonKeyService.derive(userId, biz, addressIndex);
        org.ton.ton4j.address.Address rawAddress = tonKeyService.wallet(userId, biz, addressIndex).getAddress();
        boolean testnet = normalize(profile.getNetwork()).contains("test");
        String address = rawAddress.toString(true, true, false, testnet);
        return baseRecord(profile, userId, biz, addressIndex, address, address, key.derivationPath(), walletRole);
    }

    /**
     * 构建或生成 {@code deriveXrp} 对应的结果，并执行输入和状态校验。
     */
    private ChainAddressRecord deriveXrp(AccountChainProfile profile, long userId, int biz,
                                         long addressIndex, String walletRole) {
        String address = xrpKeyService.address(profile, userId, biz, addressIndex);
        return baseRecord(profile, userId, biz, addressIndex, address, address,
                derivationPath(profile, userId, biz, addressIndex), walletRole);
    }

    /**
     * 构建或生成 {@code deriveCardano} 对应的结果，并执行输入和状态校验。
     */
    private ChainAddressRecord deriveCardano(AccountChainProfile profile, long userId, int biz,
                                             long addressIndex, String walletRole) {
        Ed25519DerivedKey key = cardanoKeyService.derive(userId, biz, addressIndex);
        String address = CardanoKeyService.enterpriseAddress(key.publicKey(), isMainnet(normalize(profile.getNetwork())));
        return baseRecord(profile, userId, biz, addressIndex, address, address, key.derivationPath(), walletRole);
    }

    /**
     * 构建或生成 {@code deriveNear} 对应的结果，并执行输入和状态校验。
     */
    private ChainAddressRecord deriveNear(AccountChainProfile profile, long userId, int biz,
                                          long addressIndex, String walletRole) {
        Ed25519DerivedKey key = nearKeyService.derive(userId, biz, addressIndex);
        String address = NearKeyService.address(key.publicKey());
        return baseRecord(profile, userId, biz, addressIndex, address, address, key.derivationPath(), walletRole);
    }

    /**
     * 构建或生成 {@code derivePolkadot} 对应的结果，并执行输入和状态校验。
     */
    private ChainAddressRecord derivePolkadot(AccountChainProfile profile, long userId, int biz,
                                              long addressIndex, String walletRole) {
        Ed25519DerivedKey key = polkadotKeyService.derive(userId, biz, addressIndex);
        String address = PolkadotKeyService.ss58Address(key.publicKey(), polkadotSs58Prefix(profile));
        return baseRecord(profile, userId, biz, addressIndex, address, address, key.derivationPath(), walletRole);
    }
    /**
     * 构建或生成 {@code deriveMonero} 对应的结果，并执行输入和状态校验。
     */
    private ChainAddressRecord deriveMonero(long userId, int biz, long addressIndex, String walletRole) {
        return moneroAddressService.createNativeAddress(userId, biz, addressIndex, walletRole);
    }

    /**
     * 执行 {@code baseRecord} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private ChainAddressRecord baseRecord(AccountChainProfile profile, long userId, int biz,
                                          long addressIndex, String address, String ownerAddress,
                                          String derivationPath, String walletRole) {
        return ChainAddressRecord.builder()
                .chain(profile.getChain())
                .assetSymbol(profile.getNativeSymbol())
                .accountId(ownerAddress == null ? String.valueOf(userId) : ownerAddress)
                .userId(userId)
                .biz(biz)
                .addressIndex(addressIndex)
                .address(address)
                .ownerAddress(ownerAddress)
                .derivationPath(derivationPath)
                .walletRole(walletRole)
                .enabled(true)
                .build();
    }
    /**
     * 执行 {@code sameAddress} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private boolean sameAddress(AccountChainProfile profile, String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        String family = normalize(profile.getFamily());
        if ("evm".equals(family) || "hypercore".equals(family)) {
            return left.equalsIgnoreCase(right);
        }
        return left.equals(right);
    }
    /**
     * 判断 {@code isDefaultHotAddressRow} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private boolean isDefaultHotAddressRow(AccountChainProfile profile, ChainAddressRecord record) {
        return profile.getNativeSymbol().equalsIgnoreCase(record.getAssetSymbol())
                && record.getAddressIndex() != null
                && record.getAddressIndex().equals(HotWalletRules.DEFAULT_HOT_ADDRESS_INDEX)
                && HotWalletRules.DEFAULT_HOT_WALLET_ROLE.equals(record.getWalletRole());
    }
    /**
     * 执行 {@code derivationPath} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private String derivationPath(AccountChainProfile profile, long userId, int biz, long index) {
        return String.format("m/44/%d/%d/%d/%d", derivationCoinType(profile), biz, userId, index);
    }
    /**
     * 执行 {@code derivationCoinType} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private int derivationCoinType(AccountChainProfile profile) {
        return ChainType.derivationCoinType(profile.getChain(), profile.getBip44CoinType());
    }
    /**
     * 执行 {@code polkadotSs58Prefix} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private int polkadotSs58Prefix(AccountChainProfile profile) {
        if (profile.getChainId() != null && profile.getChainId() >= 0 && profile.getChainId() <= 16383) {
            return Math.toIntExact(profile.getChainId());
        }
        return isMainnet(normalize(profile.getNetwork())) ? 0 : 42;
    }
    /**
     * 转换或计算 {@code normalize} 对应的值，统一金额、格式和边界规则。
     */
    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
    /**
     * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
     */
    private enum AddressFormat {
        /**
         * 定义 {@code EVM} 常量，作为当前组件统一使用的固定协议、网络或配置值。
         */
        EVM,
        /**
         * 定义 {@code TRON} 常量，作为当前组件统一使用的固定协议、网络或配置值。
         */
        TRON,
        /**
         * 定义 {@code XRP} 常量，作为当前组件统一使用的固定协议、网络或配置值。
         */
        XRP
    }
}
