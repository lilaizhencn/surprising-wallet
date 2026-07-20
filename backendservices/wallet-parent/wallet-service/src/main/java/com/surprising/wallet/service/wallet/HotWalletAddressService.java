package com.surprising.wallet.service.wallet;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.service.chain.aptos.AptosKeyService;
import com.surprising.wallet.service.chain.cardano.CardanoKeyService;
import com.surprising.wallet.service.chain.monero.MoneroAddressService;
import com.surprising.wallet.service.chain.near.NearKeyService;
import com.surprising.wallet.service.chain.polkadot.PolkadotKeyService;
import com.surprising.wallet.service.chain.solana.SolanaKeyService;
import com.surprising.wallet.service.chain.sui.SuiKeyService;
import com.surprising.wallet.service.chain.ton.TonKeyService;
import com.surprising.wallet.service.chain.xrp.XrpKeyService;
import com.surprising.wallet.service.config.PubKeyConfig;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
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
import org.ethereum.crypto.EthECKey;
import org.p2p.solanaj.core.PublicKey;
import org.spongycastle.util.encoders.Hex;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.tron.TronWalletApi;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class HotWalletAddressService {
    private final ChainJdbcRepository repository;
    private final PubKeyConfig pubKeyConfig;
    private final SolanaKeyService solanaKeyService;
    private final SuiKeyService suiKeyService;
    private final AptosKeyService aptosKeyService;
    private final TonKeyService tonKeyService;
    private final XrpKeyService xrpKeyService;
    private final CardanoKeyService cardanoKeyService;
    private final NearKeyService nearKeyService;
    private final PolkadotKeyService polkadotKeyService;
    private final MoneroAddressService moneroAddressService;

    public Optional<ChainAddressRecord> findDefaultHotAddress(String chain, String assetSymbol) {
        return repository.findChainAddress(
                chain,
                assetSymbol,
                HotWalletRules.DEFAULT_HOT_USER_ID,
                HotWalletRules.DEFAULT_HOT_BIZ,
                HotWalletRules.DEFAULT_HOT_ADDRESS_INDEX,
                HotWalletRules.DEFAULT_HOT_WALLET_ROLE);
    }

    public ChainAddressRecord requireVerifiedDefaultHotAddress(AccountChainProfile profile) {
        ChainAddressRecord expected = deriveDefaultHotAddress(profile);
        return requireDefaultHotAddressMatches(profile, expected);
    }

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

    public ChainAddressRecord deriveDefaultHotAddress(AccountChainProfile profile) {
        return deriveAddress(
                profile,
                HotWalletRules.DEFAULT_HOT_USER_ID,
                HotWalletRules.DEFAULT_HOT_BIZ,
                HotWalletRules.DEFAULT_HOT_ADDRESS_INDEX,
                HotWalletRules.DEFAULT_HOT_WALLET_ROLE);
    }

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
            default -> throw new IllegalStateException("unsupported hot wallet derivation family: "
                    + profile.getChain() + "/" + profile.getFamily());
        };
    }

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

    private boolean isMainnet(String network) {
        return "main".equals(network) || "mainnet".equals(network);
    }

    private ChainAddressRecord deriveSecp256k1(AccountChainProfile profile, long userId, int biz,
                                               long addressIndex, String walletRole, AddressFormat format) {
        ECKey ecKey = pubKeyConfig.node2().getChild(44)
                .getChild(derivationCoinType(profile))
                .getChild(biz)
                .getChild(Math.toIntExact(userId))
                .getChild(Math.toIntExact(addressIndex))
                .getEcKey();
        String address = switch (format) {
            case EVM -> "0x" + Hex.toHexString(EthECKey.fromPublicOnly(ecKey.getPubKey()).getAddress());
            case TRON -> TronWalletApi.getAddress(ecKey.getPubKey());
            case XRP -> XrpKeyService.address(ecKey);
        };
        return baseRecord(profile, userId, biz, addressIndex, address, address,
                derivationPath(profile, userId, biz, addressIndex), walletRole);
    }

    private ChainAddressRecord deriveSolana(AccountChainProfile profile, long userId, int biz,
                                            long addressIndex, String walletRole) {
        Ed25519DerivedKey key = solanaKeyService.derive(addressIndex);
        String address = new PublicKey(key.publicKey()).toBase58();
        return baseRecord(profile, userId, biz, addressIndex, address, address, key.derivationPath(), walletRole);
    }

    private ChainAddressRecord deriveSui(AccountChainProfile profile, long userId, int biz,
                                         long addressIndex, String walletRole) {
        Ed25519DerivedKey key = suiKeyService.derive(addressIndex);
        String address = SuiKeyService.address(key.publicKey());
        return baseRecord(profile, userId, biz, addressIndex, address, address, key.derivationPath(), walletRole);
    }

    private ChainAddressRecord deriveAptos(AccountChainProfile profile, long userId, int biz,
                                           long addressIndex, String walletRole) {
        Ed25519DerivedKey key = aptosKeyService.derive(addressIndex);
        String address = AptosKeyService.address(key.publicKey());
        return baseRecord(profile, userId, biz, addressIndex, address, address, key.derivationPath(), walletRole);
    }

    private ChainAddressRecord deriveTon(AccountChainProfile profile, long userId, int biz,
                                         long addressIndex, String walletRole) {
        Ed25519DerivedKey key = tonKeyService.derive(addressIndex);
        org.ton.ton4j.address.Address rawAddress = tonKeyService.wallet(addressIndex).getAddress();
        boolean testnet = normalize(profile.getNetwork()).contains("test");
        String address = rawAddress.toString(true, true, false, testnet);
        return baseRecord(profile, userId, biz, addressIndex, address, address, key.derivationPath(), walletRole);
    }

    private ChainAddressRecord deriveXrp(AccountChainProfile profile, long userId, int biz,
                                         long addressIndex, String walletRole) {
        String address = xrpKeyService.address(profile, userId, biz, addressIndex);
        return baseRecord(profile, userId, biz, addressIndex, address, address,
                derivationPath(profile, userId, biz, addressIndex), walletRole);
    }

    private ChainAddressRecord deriveCardano(AccountChainProfile profile, long userId, int biz,
                                             long addressIndex, String walletRole) {
        Ed25519DerivedKey key = cardanoKeyService.derive(userId, biz, addressIndex);
        String address = CardanoKeyService.enterpriseAddress(key.publicKey(), isMainnet(normalize(profile.getNetwork())));
        return baseRecord(profile, userId, biz, addressIndex, address, address, key.derivationPath(), walletRole);
    }

    private ChainAddressRecord deriveNear(AccountChainProfile profile, long userId, int biz,
                                          long addressIndex, String walletRole) {
        Ed25519DerivedKey key = nearKeyService.derive(userId, biz, addressIndex);
        String address = NearKeyService.address(key.publicKey());
        return baseRecord(profile, userId, biz, addressIndex, address, address, key.derivationPath(), walletRole);
    }

    private ChainAddressRecord derivePolkadot(AccountChainProfile profile, long userId, int biz,
                                              long addressIndex, String walletRole) {
        Ed25519DerivedKey key = polkadotKeyService.derive(userId, biz, addressIndex);
        String address = PolkadotKeyService.ss58Address(key.publicKey(), polkadotSs58Prefix(profile));
        return baseRecord(profile, userId, biz, addressIndex, address, address, key.derivationPath(), walletRole);
    }

    private ChainAddressRecord deriveMonero(long userId, int biz, long addressIndex, String walletRole) {
        return moneroAddressService.createNativeAddress(userId, biz, addressIndex, walletRole);
    }

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

    private boolean isDefaultHotAddressRow(AccountChainProfile profile, ChainAddressRecord record) {
        return profile.getNativeSymbol().equalsIgnoreCase(record.getAssetSymbol())
                && record.getAddressIndex() != null
                && record.getAddressIndex().equals(HotWalletRules.DEFAULT_HOT_ADDRESS_INDEX)
                && HotWalletRules.DEFAULT_HOT_WALLET_ROLE.equals(record.getWalletRole());
    }

    private String derivationPath(AccountChainProfile profile, long userId, int biz, long index) {
        return String.format("m/44/%d/%d/%d/%d", derivationCoinType(profile), biz, userId, index);
    }

    private int derivationCoinType(AccountChainProfile profile) {
        return ChainType.derivationCoinType(profile.getChain(), profile.getBip44CoinType());
    }

    private int polkadotSs58Prefix(AccountChainProfile profile) {
        if (profile.getChainId() != null && profile.getChainId() >= 0 && profile.getChainId() <= 16383) {
            return Math.toIntExact(profile.getChainId());
        }
        return isMainnet(normalize(profile.getNetwork())) ? 0 : 42;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private enum AddressFormat {
        EVM,
        TRON,
        XRP
    }
}
