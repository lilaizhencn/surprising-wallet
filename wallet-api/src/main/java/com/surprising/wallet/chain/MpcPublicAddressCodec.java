package com.surprising.wallet.chain;

import com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec;
import com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec;
import com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec;
import com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec;
import com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec;
import com.surprising.wallet.sdk.signing.ton.MpcTonTransactionCodec;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork;
import com.surprising.wallet.sdk.signing.MoneroPublicAddressCodec;
import java.util.Locale;
import java.util.Objects;
import org.bitcoinj.crypto.ECKey;
import org.p2p.solanaj.core.PublicKey;
import org.tron.TronWalletApi;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

/** Converts a joint public key to a chain address without creating or loading signing material. */
public final class MpcPublicAddressCodec {
    private MpcPublicAddressCodec() { }

    public static String encode(AccountChainProfile profile, byte[] publicKey) {
        Objects.requireNonNull(profile, "MPC chain profile is required");
        Objects.requireNonNull(publicKey, "MPC public key is required");
        var chain = ChainType.valueOf(profile.getChain().toUpperCase(Locale.ROOT));
        if (chain == ChainType.XMR) return MoneroPublicAddressCodec.encode(publicKey,profile.getNetwork(),true);
        if (chain.isEvm() || switch (chain) { case BTC, LTC, DOGE, BCH, TRON, XRP, HYPERCORE -> true; default -> false; }) {
            if (publicKey.length != 33 || (publicKey[0] != 2 && publicKey[0] != 3)) {
                throw new IllegalArgumentException("compressed MPC secp256k1 public key is required");
            }
            var key = ECKey.fromPublicOnly(publicKey);
            if (chain.isEvm() || chain == ChainType.HYPERCORE) {
                return "0x" + Keys.getAddress(Sign.publicFromPoint(key.decompress().getPubKey()));
            }
            return switch (chain) {
                case TRON -> TronWalletApi.getAddress(key.getPubKey());
                case XRP -> org.xrpl.xrpl4j.crypto.keys.PublicKey.fromBase16EncodedPublicKey(
                        java.util.HexFormat.of().formatHex(publicKey).toUpperCase(Locale.ROOT)).deriveAddress().value();
                case BTC, LTC, DOGE, BCH -> com.surprising.wallet.sdk.signing.MpcUtxoTransactionCodec.address(chain.name(),BitcoinLikeNetwork.parse(profile.getNetwork()),java.util.HexFormat.of().formatHex(publicKey));
                default -> throw new IllegalArgumentException("unsupported secp256k1 address");
            };
        }
        if (publicKey.length != 32) throw new IllegalArgumentException("MPC Ed25519 public key must be 32 bytes");
        String network = Objects.requireNonNull(profile.getNetwork(), "MPC network is required").toLowerCase(Locale.ROOT);
        boolean mainnet = network.equals("main") || network.equals("mainnet");
        return switch (chain) {
            case SOLANA -> new PublicKey(publicKey).toBase58();
            case SUI -> MpcSuiTransactionCodec.address(publicKey);
            case APTOS -> com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec.address(publicKey);
            case ADA -> MpcCardanoTransactionCodec.address(publicKey, mainnet);
            case NEAR -> MpcNearTransactionCodec.address(publicKey);
            case DOT -> {
                long prefix = profile.getChainId() == null ? (mainnet ? 0 : 42) : profile.getChainId();
                yield MpcPolkadotTransactionCodec.address(publicKey, Math.toIntExact(prefix));
            }
            case TON -> MpcTonTransactionCodec.address(network, publicKey);
            default -> throw new IllegalArgumentException("unsupported MPC address chain");
        };
    }
}
