package com.surprising.wallet.approval.chain;

import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.ApprovalAssetSnapshot;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.sdk.signing.*;
import com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec;
import com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec;
import com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec;
import com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec;
import com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec;
import com.surprising.wallet.sdk.signing.ton.MpcTonTransactionCodec;
import java.util.*;
import org.bitcoinj.crypto.ECKey;
import org.web3j.crypto.*;

/** Public role ownership derived from independently enrolled keys, never from Wallet's address table. */
public final class RegisteredMpcAddresses {
    private RegisteredMpcAddresses() { }
    public static String address(RegisteredMpcKey key,ApprovalAssetSnapshot asset) {
        var chain=ChainType.valueOf(asset.chain());byte[] publicKey=HexFormat.of().parseHex(key.publicKeyHex());
        if(chain.isEvm() || chain==ChainType.HYPERCORE)return "0x"+Keys.getAddress(Sign.publicFromPoint(ECKey.fromPublicOnly(publicKey).decompress().getPubKey()));
        if(chain.isBitcoinLike())return MpcUtxoTransactionCodec.address(chain.name(),com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork.parse(asset.network()),key.publicKeyHex());
        return switch(chain) {
            case TRON -> org.tron.TronWalletApi.getAddress(publicKey);
            case XRP -> org.xrpl.xrpl4j.crypto.keys.PublicKey.fromBase16EncodedPublicKey(key.publicKeyHex().toUpperCase(Locale.ROOT)).deriveAddress().value();
            case SOLANA -> new org.p2p.solanaj.core.PublicKey(publicKey).toBase58();
            case TON -> MpcTonTransactionCodec.canonicalAddress(MpcTonTransactionCodec.address(asset.network(),publicKey));
            case APTOS -> MpcAptosTransactionCodec.address(publicKey);
            case SUI -> MpcSuiTransactionCodec.address(publicKey);
            case ADA -> MpcCardanoTransactionCodec.address(publicKey,Set.of("main","mainnet").contains(asset.network()));
            case NEAR -> MpcNearTransactionCodec.address(publicKey);
            case DOT -> MpcPolkadotTransactionCodec.address(publicKey,Set.of("main","mainnet","polkadot").contains(asset.network())?0:42);
            case XMR -> MoneroPublicAddressCodec.encode(publicKey,asset.network(),true);
            default -> throw new IllegalArgumentException("explicit independent public address codec required");
        };
    }
    public static boolean matches(RegisteredMpcKey key,ApprovalAssetSnapshot asset,String value) {
        return key!=null && key.state().equals("ACTIVE") && matchesIdentity(key,asset,value);
    }
    /** Public address identity only; it does not grant an inactive key any signing rights. */
    public static boolean matchesIdentity(RegisteredMpcKey key,ApprovalAssetSnapshot asset,String value) {
        if(key==null || !key.keyGroup().equals(ChainType.valueOf(asset.chain()).isEvm()?"EVM":asset.chain()))return false;
        var chain=ChainType.valueOf(asset.chain());
        if(chain==ChainType.DOT)return Arrays.equals(MpcPolkadotTransactionCodec.account(value),HexFormat.of().parseHex(key.publicKeyHex()));
        String actual=address(key,asset);
        if(chain==ChainType.TON)return actual.equals(MpcTonTransactionCodec.canonicalAddress(value));
        if(chain==ChainType.APTOS)return actual.equals(MpcAptosTransactionCodec.canonicalAddress(value));
        if(chain==ChainType.SUI)return actual.equals(MpcSuiTransactionCodec.canonicalAddress(value));
        return chain.isEvm() || chain==ChainType.HYPERCORE?actual.equalsIgnoreCase(value):actual.equals(value);
    }
}
