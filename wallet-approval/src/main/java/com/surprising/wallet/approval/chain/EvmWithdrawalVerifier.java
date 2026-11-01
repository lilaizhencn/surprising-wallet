package com.surprising.wallet.approval.chain;

import com.surprising.wallet.common.approval.WithdrawalIntent;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.bitcoinj.crypto.ECKey;
import org.web3j.crypto.Keys;
import org.web3j.crypto.transaction.type.Transaction1559;

/** Decodes actual unsigned wire bytes; caller-supplied semantic amount/recipient metadata is never trusted. */
public final class EvmWithdrawalVerifier {
    private static final HexFormat HEX=HexFormat.of();
    private EvmWithdrawalVerifier() { }

    /** Registry arguments must come from the authority's independent registry, never request JSON. */
    public static Verified verify(WithdrawalIntent intent,RegisteredAsset asset,RegisteredKey key,
            MpcClient.Request execution,String unsignedHex) {
        require(intent.tenantId().equals(key.tenantId()) && execution.tenantId().equals(key.tenantId())
                && execution.keyId().equals(key.keyId()) && execution.epoch()==key.epoch()
                && execution.operation()==MpcClient.Operation.SIGN && execution.algorithm()==MpcClient.Algorithm.ECDSA_SECP256K1,
                "EVM execution key or tenant differs");
        require(intent.chain().equals(asset.chain()) && intent.network().equals(asset.network())
                && intent.assetSymbol().equals(asset.symbol()),"EVM registered network or asset differs");
        require(unsignedHex!=null && unsignedHex.length()<=2*1024*1024 && unsignedHex.matches("(?:[0-9a-f]{2})+"),"invalid EVM candidate encoding");
        var raw=MpcEvmTransactionCodec.restore(HEX.parseHex(unsignedHex),asset.chainId());
        require(!raw.getType().isEip7702(),"EIP-7702 requires its independent batch and delegation policy");
        byte[] digest=MpcEvmTransactionCodec.digest(raw,asset.chainId());
        require(MessageDigest.isEqual(digest,HEX.parseHex(execution.messageHex())),"EVM execution message differs from actual wire bytes");
        require(raw.getNonce().signum()>=0 && raw.getNonce().bitLength()<=64,"EVM nonce exceeds bounds");
        String recipient=address(intent.recipient());BigInteger amount=atomic(intent.amount(),asset.decimals());
        require(amount.signum()>0 && amount.bitLength()<=256,"EVM amount exceeds bounds");
        String calldata=raw.getData()==null?"":raw.getData().toLowerCase(Locale.ROOT).replaceFirst("^0x","");
        if(asset.contract()==null) {
            require(address(raw.getTo()).equals(recipient) && raw.getValue().equals(amount) && calldata.isEmpty(),"EVM native payment differs from approved intent");
        } else {
            String expected="a9059cbb"+"0".repeat(24)+recipient.substring(2)+String.format("%064x",amount);
            require(address(raw.getTo()).equals(asset.contract()) && raw.getValue().signum()==0 && calldata.equals(expected),
                    "EVM token transfer differs from approved recipient, contract or amount");
        }
        BigInteger price=raw.getTransaction() instanceof Transaction1559 dynamic?dynamic.getMaxFeePerGas():raw.getGasPrice();
        require(price!=null && price.signum()>0 && price.bitLength()<=256 && raw.getGasLimit().signum()>0 && raw.getGasLimit().bitLength()<=64,
                "EVM gas bounds differ");
        if(raw.getTransaction() instanceof Transaction1559 dynamic)
            require(dynamic.getMaxPriorityFeePerGas().signum()>=0 && dynamic.getMaxPriorityFeePerGas().compareTo(price)<=0,"EVM priority fee exceeds maximum");
        BigInteger fee=price.multiply(raw.getGasLimit());
        BigInteger reviewedLimit=atomic(intent.maximumNetworkFee(),asset.nativeDecimals()),registryLimit=atomic(asset.networkFeeLimit(),asset.nativeDecimals());
        require(fee.compareTo(reviewedLimit)<=0 && fee.compareTo(registryLimit)<=0,"EVM maximum fee exceeds reviewed or independent policy ceiling");
        var publicPoint=ECKey.fromPublicOnly(HEX.parseHex(key.publicKeyHex())).getPubKeyPoint().getEncoded(false);
        String sender="0x"+Keys.getAddress(HEX.formatHex(publicPoint,1,publicPoint.length));
        return new Verified(HEX.formatHex(digest),sender,recipient,amount.toString(),fee.toString(),raw.getNonce().toString());
    }
    public record RegisteredAsset(String chain,String network,long chainId,String symbol,String contract,int decimals,int nativeDecimals,BigDecimal networkFeeLimit) {
        public RegisteredAsset {
            require(chain!=null && ChainType.valueOf(chain).isEvm() && chainId>0 && network!=null && !network.isBlank() && symbol!=null && !symbol.isBlank(),"invalid EVM registered domain");
            require(decimals>=0 && decimals<=36 && nativeDecimals>=0 && nativeDecimals<=36 && (contract!=null || decimals==nativeDecimals),"invalid EVM registered precision");
            require(networkFeeLimit!=null && networkFeeLimit.signum()>0,"independent EVM fee ceiling required");
            if(contract!=null) contract=address(contract);
        }
    }
    public record RegisteredKey(UUID tenantId,UUID keyId,int epoch,String publicKeyHex) {
        public RegisteredKey {
            require(tenantId!=null && keyId!=null && epoch>0 && publicKeyHex!=null && publicKeyHex.matches("0[23][0-9a-f]{64}"),"invalid independently registered EVM key");
            ECKey.fromPublicOnly(HEX.parseHex(publicKeyHex)).getPubKeyPoint().getEncoded(true);
        }
    }
    public record Verified(String messageHex,String sender,String recipient,String amountAtomic,String maximumFeeAtomic,String nonce) { }
    private static BigInteger atomic(BigDecimal amount,int decimals) {
        try { return amount.movePointRight(decimals).toBigIntegerExact(); }
        catch(ArithmeticException failure) { throw new IllegalArgumentException("EVM amount exceeds registered precision",failure); }
    }
    private static String address(String value) {
        require(value!=null && value.matches("0x[0-9a-fA-F]{40}"),"invalid EVM payment address");return value.toLowerCase(Locale.ROOT);
    }
    private static void require(boolean value,String message) { if(!value)throw new IllegalArgumentException(message); }
}
