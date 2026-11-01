package com.surprising.wallet.approval;

import com.surprising.wallet.approval.chain.EvmWithdrawalVerifier;
import com.surprising.wallet.common.approval.WithdrawalIntent;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.RawTransaction;
import static org.junit.jupiter.api.Assertions.*;

class EvmWithdrawalVerifierTest {
    /** Actual acceptance fixture: encode the real wire with the same pinned codec used by Wallet. */
    public static void main(String[] args) throws Exception {
        var json=new tools.jackson.databind.ObjectMapper();var input=json.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0])));
        var i=json.treeToValue(input.path("intent"),WithdrawalIntent.class);
        long chainId=11155111;
        var raw=RawTransaction.createTransaction(BigInteger.ONE,BigInteger.valueOf(300_000_000),BigInteger.valueOf(70000),TOKEN,BigInteger.ZERO,
                "a9059cbb"+"0".repeat(24)+i.recipient().substring(2)+String.format("%064x",i.amount().movePointRight(6).toBigIntegerExact()));
        String digest=HexFormat.of().formatHex(MpcEvmTransactionCodec.digest(raw,chainId));
        var envelope=new java.util.LinkedHashMap<String,Object>();envelope.put("chainId",chainId);
        envelope.put("unsignedHex",HexFormat.of().formatHex(MpcEvmTransactionCodec.unsignedPayload(raw,chainId)));
        envelope.put("symbol","USDC");envelope.put("contract",TOKEN);envelope.put("amount",i.amount());envelope.put("assetDecimals",6);envelope.put("nativeDecimals",18);
        var key=UUID.fromString(input.path("keyId").asText());int epoch=input.path("epoch").asInt();
        var command=new com.surprising.wallet.common.signing.MpcSignCommand(i.tenantId(),key,epoch,ChainType.ETH,
                com.surprising.wallet.common.signing.MpcSignCommand.BusinessType.WITHDRAWAL,i.businessReference(),"transaction",
                com.surprising.wallet.common.signing.MpcSignCommand.Purpose.TRANSACTION,"EVM_TX_JSON",HexFormat.of().formatHex(json.writeValueAsBytes(envelope)),digest);
        var execution=new MpcClient.Request(UUID.randomUUID(),i.tenantId(),key,epoch,MpcClient.Operation.SIGN,MpcClient.Algorithm.ECDSA_SECP256K1,
                json.convertValue(input.path("online"),new tools.jackson.core.type.TypeReference<List<String>>() {}),digest);
        System.out.println(json.writeValueAsString(new com.surprising.wallet.common.approval.SigningCandidate(UUID.randomUUID(),List.of(i.id()),command,execution,null,null)));
    }
    private static final String TO="0x1111111111111111111111111111111111111111",OTHER="0x2222222222222222222222222222222222222222",TOKEN="0x3333333333333333333333333333333333333333";
    private final UUID tenant=UUID.randomUUID(),keyId=UUID.randomUUID();
    private final EvmWithdrawalVerifier.RegisteredKey key=new EvmWithdrawalVerifier.RegisteredKey(tenant,keyId,1,"0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798");
    private WithdrawalIntent intent(String chain,String symbol) {
        return new WithdrawalIntent(UUID.randomUUID(),tenant,"CW-test",chain,"sepolia",symbol,TO,BigDecimal.ONE,new BigDecimal("0.01"),"API",UUID.randomUUID(),"customer-42", "external-test", "ADDRESS");
    }
    private EvmWithdrawalVerifier.RegisteredAsset asset(String chain,String contract,int decimals) {
        return new EvmWithdrawalVerifier.RegisteredAsset(chain,"sepolia",11155111,"ETH"+(contract==null?"":"_TOKEN"),contract,decimals,18,new BigDecimal("0.02"));
    }
    private EvmWithdrawalVerifier.Verified verify(WithdrawalIntent intent,EvmWithdrawalVerifier.RegisteredAsset asset,RawTransaction raw) {
        var request=new MpcClient.Request(UUID.randomUUID(),tenant,keyId,1,MpcClient.Operation.SIGN,MpcClient.Algorithm.ECDSA_SECP256K1,
                List.of("a".repeat(64),"b".repeat(64)),HexFormat.of().formatHex(MpcEvmTransactionCodec.digest(raw,11155111)));
        return EvmWithdrawalVerifier.verify(intent,asset,key,request,HexFormat.of().formatHex(MpcEvmTransactionCodec.unsignedPayload(raw,11155111)));
    }
    private RawTransaction nativePayment(String to,BigInteger value,BigInteger price) {
        return RawTransaction.createEtherTransaction(BigInteger.ONE,price,BigInteger.valueOf(21000),to,value);
    }
    @Test void allEvmFamiliesDecodeActualWireAndMatchReviewedNativePayment() {
        for(var chain:Arrays.stream(ChainType.values()).filter(ChainType::isEvm).toList()) {
            var verified=verify(intent(chain.name(),"ETH"),asset(chain.name(),null,18),nativePayment(TO,BigInteger.TEN.pow(18),BigInteger.valueOf(1_000_000_000)));
            assertEquals(TO,verified.recipient());assertEquals("1000000000000000000",verified.amountAtomic());
        }
    }
    @Test void nativeRecipientAmountCalldataAndFeeChangesFailEvenWithMatchingNewDigest() {
        var intent=intent("ETH","ETH");var asset=asset("ETH",null,18);
        assertThrows(IllegalArgumentException.class,()->verify(intent,asset,nativePayment(OTHER,BigInteger.TEN.pow(18),BigInteger.ONE)));
        assertThrows(IllegalArgumentException.class,()->verify(intent,asset,nativePayment(TO,BigInteger.TWO.multiply(BigInteger.TEN.pow(18)),BigInteger.ONE)));
        assertThrows(IllegalArgumentException.class,()->verify(intent,asset,nativePayment(TO,BigInteger.TEN.pow(18),BigInteger.TEN.pow(15))));
        assertThrows(IllegalArgumentException.class,()->verify(intent,asset,RawTransaction.createTransaction(BigInteger.ONE,BigInteger.ONE,BigInteger.valueOf(21000),TO,BigInteger.TEN.pow(18),"a9059cbb")));
    }
    @Test void tokenApprovalRequiresExactRegisteredContractAndTransferCalldata() {
        var intent=intent("ETH","ETH_TOKEN");var asset=asset("ETH",TOKEN,6);
        String data="a9059cbb"+"0".repeat(24)+TO.substring(2)+String.format("%064x",BigInteger.valueOf(1_000_000));
        var raw=RawTransaction.createTransaction(BigInteger.ONE,BigInteger.valueOf(1_000_000_000),BigInteger.valueOf(70000),TOKEN,BigInteger.ZERO,data);
        assertEquals("1000000",verify(intent,asset,raw).amountAtomic());
        for(var candidate:List.of(
                RawTransaction.createTransaction(BigInteger.ONE,BigInteger.ONE,BigInteger.valueOf(70000),OTHER,BigInteger.ZERO,data),
                RawTransaction.createTransaction(BigInteger.ONE,BigInteger.ONE,BigInteger.valueOf(70000),TOKEN,BigInteger.ONE,data),
                RawTransaction.createTransaction(BigInteger.ONE,BigInteger.ONE,BigInteger.valueOf(70000),TOKEN,BigInteger.ZERO,data+"00"),
                RawTransaction.createTransaction(BigInteger.ONE,BigInteger.ONE,BigInteger.valueOf(70000),TOKEN,BigInteger.ZERO,data.replace("a9059cbb","095ea7b3"))))
            assertThrows(IllegalArgumentException.class,()->verify(intent,asset,candidate));
    }
    @Test void dynamicFeeWireIsCheckedAgainstIndependentCeilingAndKeyScope() {
        var raw=RawTransaction.createEtherTransaction(11155111,BigInteger.ONE,BigInteger.valueOf(21000),TO,BigInteger.TEN.pow(18),BigInteger.ONE,BigInteger.valueOf(2_000_000_000));
        var intent=intent("ETH","ETH");var asset=asset("ETH",null,18);
        assertEquals(TO,verify(intent,asset,raw).recipient());
        var wrongScope=new MpcClient.Request(UUID.randomUUID(),tenant,UUID.randomUUID(),1,MpcClient.Operation.SIGN,MpcClient.Algorithm.ECDSA_SECP256K1,
                List.of("a".repeat(64),"b".repeat(64)),HexFormat.of().formatHex(MpcEvmTransactionCodec.digest(raw,11155111)));
        assertThrows(IllegalArgumentException.class,()->EvmWithdrawalVerifier.verify(intent,asset,key,wrongScope,HexFormat.of().formatHex(MpcEvmTransactionCodec.unsignedPayload(raw,11155111))));
        var wrongNetwork=new EvmWithdrawalVerifier.RegisteredAsset("ETH","mainnet",1,"ETH",null,18,18,new BigDecimal("0.01"));
        assertThrows(IllegalArgumentException.class,()->verify(intent,wrongNetwork,raw));
    }
}
