package com.surprising.wallet.common.approval;

import com.surprising.wallet.common.approval.WithdrawalIntent;

import com.surprising.wallet.common.chain.ChainType;
import java.math.BigDecimal;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

/** Public asset/network facts from the independent host profile, frozen with the reviewed request. No RPC secrets. */
public record ApprovalAssetSnapshot(String chain,String network,String symbol,String nativeSymbol,String networkIdentity,
        String tokenIdentifier,int decimals,int nativeDecimals,
        @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal networkFeeLimit) {
    public ApprovalAssetSnapshot {
        if(chain==null || !chain.equals(ChainType.valueOf(chain).name()) || network==null || !network.matches("[a-zA-Z0-9_.:-]{1,128}")
                || symbol==null || !symbol.matches("[A-Z][A-Z0-9_.-]{0,31}") || nativeSymbol==null || !nativeSymbol.matches("[A-Z][A-Z0-9_.-]{0,31}")
                || networkIdentity==null || !networkIdentity.matches("[a-zA-Z0-9_.:-]{1,256}") || decimals<0 || decimals>36 || nativeDecimals<0 || nativeDecimals>36)
            throw new IllegalArgumentException("invalid independently configured asset domain");
        networkFeeLimit=WithdrawalIntent.number(networkFeeLimit,true);
        try { networkFeeLimit.movePointRight(nativeDecimals).toBigIntegerExact(); }
        catch(ArithmeticException invalid) { throw new IllegalArgumentException("independent fee ceiling exceeds native asset precision",invalid); }
        if(tokenIdentifier!=null && (tokenIdentifier.isBlank() || tokenIdentifier.length()>512 || tokenIdentifier.chars().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("invalid independently configured asset identifier");
        if(tokenIdentifier==null && (!symbol.equals(nativeSymbol) || decimals!=nativeDecimals))
            throw new IllegalArgumentException("native asset identity and precision must agree");
        if(ChainType.valueOf(chain).isEvm()) {
            if(!networkIdentity.matches("eip155:[1-9][0-9]{0,18}") || Long.parseLong(networkIdentity.substring(7))<=0
                    || (tokenIdentifier!=null && !tokenIdentifier.matches("0x[0-9a-fA-F]{40}")))
                throw new IllegalArgumentException("invalid independently configured EVM network or token");
            if(tokenIdentifier!=null) tokenIdentifier=tokenIdentifier.toLowerCase(java.util.Locale.ROOT);
        }
        if(ChainType.valueOf(chain)==ChainType.APTOS) {
            if(!networkIdentity.matches("aptos:[1-9][0-9]{0,2}") || Integer.parseInt(networkIdentity.substring(6))>255
                    || !nativeSymbol.equals("APT") || nativeDecimals!=8 || decimals>18)
                throw new IllegalArgumentException("invalid independently configured Aptos chain ID or native precision");
            if(tokenIdentifier!=null)tokenIdentifier=com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec.canonicalAddress(tokenIdentifier);
        }
        if(ChainType.valueOf(chain).isBitcoinLike()) {
            com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork.parse(network);
            if(!networkIdentity.matches("utxo:[0-9a-f]{64}") || tokenIdentifier!=null || !nativeSymbol.equals(chain) || !symbol.equals(chain) || decimals!=8 || nativeDecimals!=8)
                throw new IllegalArgumentException("invalid independently configured native UTXO genesis or precision");
        }
        switch(ChainType.valueOf(chain)) {
            case SOLANA -> {
                require(networkIdentity.startsWith("solana:") && org.p2p.solanaj.utils.Base58.decode(networkIdentity.substring(7)).length==32 && nativeSymbol.equals("SOL") && nativeDecimals==9 && decimals<=18,"invalid independent Solana network/precision");
                if(tokenIdentifier!=null)new org.p2p.solanaj.core.PublicKey(tokenIdentifier);
            }
            case TON -> {
                require(networkIdentity.matches("ton:[0-9a-f]{64}") && nativeSymbol.equals("TON") && nativeDecimals==9,"invalid independent TON zero-state/precision");
                com.surprising.wallet.sdk.signing.ton.MpcTonTransactionCodec.walletId(network);
                if(tokenIdentifier!=null)tokenIdentifier=com.surprising.wallet.sdk.signing.ton.MpcTonTransactionCodec.canonicalAddress(tokenIdentifier);
            }
            case TRON -> {
                require(networkIdentity.matches("tron:[0-9a-f]{64}") && nativeSymbol.equals("TRX") && nativeDecimals==6,"invalid independent TRON genesis/precision");
                if(tokenIdentifier!=null)require(com.surprising.wallet.sdk.signing.tron.TronAddressCodec.isValidBase58(tokenIdentifier),"invalid independent TRC-20 contract");
            }
            case XRP -> {
                require(networkIdentity.matches("xrpl:(?:0|[1-9][0-9]{0,9})") && nativeSymbol.equals("XRP") && nativeDecimals==6,"invalid independent XRPL network ID/precision");
                if(tokenIdentifier!=null) {
                    String[] parts=tokenIdentifier.split(":",-1);require(parts.length==2 && parts[1].matches("[A-Za-z0-9]{3}|[0-9A-Fa-f]{40}"),"invalid independent issued currency");
                    org.xrpl.xrpl4j.model.transactions.Address.of(parts[0]).validateAddress();
                }
            }
            case NEAR -> {
                require(networkIdentity.startsWith("near:") && org.bitcoinj.base.Base58.decode(networkIdentity.substring(5)).length==32 && nativeSymbol.equals("NEAR") && nativeDecimals==24,"invalid independent NEAR genesis/precision");
                if(tokenIdentifier!=null)tokenIdentifier=com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec.account(tokenIdentifier);
            }
            case SUI -> {
                require(networkIdentity.startsWith("sui:") && org.bitcoinj.base.Base58.decode(networkIdentity.substring(4)).length==32 && nativeSymbol.equals("SUI") && nativeDecimals==9,"invalid independent Sui genesis/precision");
                if(tokenIdentifier!=null)tokenIdentifier=com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec.canonicalCoinType(tokenIdentifier);
            }
            case ADA -> {
                require(networkIdentity.matches("cardano:[0-9a-f]{64}:(?:0|[1-9][0-9]{0,9})") && nativeSymbol.equals("ADA") && nativeDecimals==6,"invalid independent Cardano genesis/magic/precision");
                if(tokenIdentifier!=null)tokenIdentifier=com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec.assetUnit(tokenIdentifier);
            }
            case DOT -> require(networkIdentity.matches("polkadot:[0-9a-f]{64}") && nativeSymbol.equals("DOT") && nativeDecimals<=18 && (tokenIdentifier==null || tokenIdentifier.matches("0|[1-9][0-9]{0,9}")),"invalid independent DOT Asset Hub genesis/precision");
            case HYPERCORE -> require(networkIdentity.equals("hyperliquid:"+(network.equals("mainnet")?"Mainnet":network.equals("testnet")?"Testnet":""))
                    && nativeSymbol.equals("USDC") && nativeDecimals==6 && tokenIdentifier!=null && tokenIdentifier.matches("[A-Za-z0-9._-]+:0x[0-9a-f]{32}"),"independent HyperCore must freeze the exact spot token, including canonical USDC");
            case XMR -> require(networkIdentity.matches("monero:[0-9a-f]{64}") && nativeSymbol.equals("XMR") && nativeDecimals==12 && tokenIdentifier==null
                    && java.util.Set.of("mainnet","testnet","stagenet","regtest").contains(network),"invalid independent Monero genesis/precision");
            default -> { }
        }
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
    public String fingerprint(ObjectMapper json) {
        var facts=new TreeMap<String,Object>();facts.put("v",1);facts.put("chain",chain);facts.put("network",network);
        facts.put("symbol",symbol);facts.put("nativeSymbol",nativeSymbol);facts.put("networkIdentity",networkIdentity);
        facts.put("tokenIdentifier",tokenIdentifier);facts.put("decimals",decimals);facts.put("nativeDecimals",nativeDecimals);
        facts.put("networkFeeLimit",networkFeeLimit.toPlainString());
        return SigningAuthorization.hash(json.writeValueAsBytes(facts));
    }
    public String bind(WithdrawalIntent intent,ObjectMapper json) {
        if(!chain.equals(intent.chain()) || !network.equals(intent.network()) || !symbol.equals(intent.assetSymbol()))
            throw new IllegalArgumentException("independent asset does not belong to the intent");
        try {
            intent.amount().movePointRight(decimals).toBigIntegerExact();
            intent.maximumNetworkFee().movePointRight(nativeDecimals).toBigIntegerExact();
        } catch(ArithmeticException invalid) { throw new IllegalArgumentException("intent exceeds independently registered asset precision",invalid); }
        if(intent.maximumNetworkFee().compareTo(networkFeeLimit)>0)
            throw new IllegalArgumentException("intent fee ceiling exceeds independent network policy");
        return SigningAuthorization.hash(("SW-APPROVAL-INTENT-V1:"+intent.hash(json)+":"+fingerprint(json)).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
