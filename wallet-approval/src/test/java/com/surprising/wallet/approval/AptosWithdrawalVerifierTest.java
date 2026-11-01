package com.surprising.wallet.approval;

import com.surprising.wallet.approval.chain.ApprovalWithdrawalVerifiers;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class AptosWithdrawalVerifierTest {
    /** Actual native fixture uses the same public BCS codec as Wallet, with a freshly created MPC public key. */
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper();var input=json.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0])));
        var intent=json.treeToValue(input.path("intent"),WithdrawalIntent.class);String publicKey=input.path("publicKeyHex").asText();
        var unsigned=new MpcAptosTransactionCodec.Unsigned(MpcAptosTransactionCodec.address(HexFormat.of().parseHex(publicKey)),0,intent.recipient(),
                intent.amount().movePointRight(8).longValueExact(),null,1000,200,Instant.now().plusSeconds(600).getEpochSecond(),2,publicKey);
        var payload=new AptosSigningPayload("APT",intent.amount(),BigDecimal.ZERO,8,unsigned);String message=HexFormat.of().formatHex(MpcAptosTransactionCodec.signingBytes(unsigned));
        UUID keyId=UUID.fromString(input.path("keyId").asText());
        var command=new MpcSignCommand(intent.tenantId(),keyId,1,ChainType.APTOS,MpcSignCommand.BusinessType.WITHDRAWAL,intent.businessReference(),"transaction",MpcSignCommand.Purpose.TRANSACTION,
                "APTOS_TX_JSON",HexFormat.of().formatHex(json.writeValueAsBytes(payload)),message);
        var execution=new MpcClient.Request(UUID.randomUUID(),intent.tenantId(),keyId,1,MpcClient.Operation.SIGN,MpcClient.Algorithm.ED25519,
                json.convertValue(input.path("online"),new tools.jackson.core.type.TypeReference<List<String>>() {}),message);
        System.out.println(json.writeValueAsString(new SigningCandidate(UUID.randomUUID(),List.of(intent.id()),command,execution,null,null)));
    }
    private static final String PUBLIC="d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final Instant NOW=Instant.parse("2026-11-01T08:00:00Z");
    private final ObjectMapper json=new ObjectMapper();
    private final UUID tenant=UUID.randomUUID(),keyId=UUID.randomUUID();
    private final RegisteredMpcKey key=new RegisteredMpcKey(tenant,keyId,"APTOS","ED25519",1,PUBLIC,null,"ACTIVE");
    private ApprovalRequest reviewed(boolean token) {
        var intent=new WithdrawalIntent(UUID.randomUUID(),tenant,"CW-aptos","APTOS","testnet",token?"USDC":"APT","0x2",BigDecimal.ONE,
                new BigDecimal("0.01"),"API",UUID.randomUUID(),"customer-42","merchant-reference","ADDRESS");
        var asset=new ApprovalAssetSnapshot("APTOS","testnet",intent.assetSymbol(),"APT","aptos:2",token?"0xcafe":null,token?6:8,8,new BigDecimal("0.02"));
        return new ApprovalRequest(intent,asset.bind(intent,json),asset,UUID.randomUUID(),1,List.of("ALL_WITHDRAWALS"),1,"APPROVED",false,NOW,NOW.plusSeconds(3600));
    }
    private MpcAptosTransactionCodec.Unsigned unsigned(boolean token) {
        return new MpcAptosTransactionCodec.Unsigned(MpcAptosTransactionCodec.address(HexFormat.of().parseHex(PUBLIC)),1,"0x2",token?1_000_000:100_000_000,
                token?"0xcafe":null,1000,200,NOW.plusSeconds(600).getEpochSecond(),2,PUBLIC);
    }
    private SigningCandidate candidate(ApprovalRequest reviewed,MpcAptosTransactionCodec.Unsigned unsigned) {
        var payload=new AptosSigningPayload(reviewed.intent().assetSymbol(),BigDecimal.ONE,BigDecimal.ZERO,reviewed.assetSnapshot().decimals(),unsigned);
        String message=HexFormat.of().formatHex(MpcAptosTransactionCodec.signingBytes(unsigned));
        var command=new MpcSignCommand(tenant,keyId,1,ChainType.APTOS,MpcSignCommand.BusinessType.WITHDRAWAL,"CW-aptos","transaction",MpcSignCommand.Purpose.TRANSACTION,
                "APTOS_TX_JSON",HexFormat.of().formatHex(json.writeValueAsBytes(payload)),message);
        var execution=new MpcClient.Request(UUID.randomUUID(),tenant,keyId,1,MpcClient.Operation.SIGN,MpcClient.Algorithm.ED25519,List.of("a".repeat(64),"c".repeat(64)),message);
        return new SigningCandidate(UUID.randomUUID(),List.of(reviewed.intent().id()),command,execution,null,null);
    }
    private ApprovalWithdrawalVerifiers.Verified verifyWire(ApprovalRequest reviewed,RegisteredMpcKey registered,SigningCandidate candidate,Instant now) {
        return new ApprovalWithdrawalVerifiers(json,org.mockito.Mockito.mock(com.surprising.wallet.approval.gateway.ApprovalChainRpcClient.class))
                .verify(reviewed,registered,java.util.Map.of(registered.keyId(),registered),candidate,now,null);
    }
    private ApprovalWithdrawalVerifiers.Verified verify(ApprovalRequest reviewed,MpcAptosTransactionCodec.Unsigned unsigned) {
        return verifyWire(reviewed,key,candidate(reviewed,unsigned),NOW);
    }
    @Test void nativeAndPrimaryFaTransactionsMatchTheActualCanonicalBcsMessageAndHardGasCeiling() {
        for(boolean token:List.of(false,true)) {
            var reviewed=reviewed(token);var result=verify(reviewed,unsigned(token));
            assertEquals(new java.math.BigInteger("200000"),result.maximumFeeAtomic());
            assertTrue(result.supportsExactRecovery());assertTrue(result.spendDomain().endsWith(":1"));
        }
    }
    @Test void customerServiceFeeDoesNotChangeTheActualApprovedTransferOrItsNativeGasCharge() {
        var reviewed=reviewed(false);var original=candidate(reviewed,unsigned(false));var c=original.command();
        for(var fee:List.of(new BigDecimal("0.1"),new BigDecimal("-0.1"))) {
            var payload=new AptosSigningPayload("APT",BigDecimal.ONE,fee,8,unsigned(false));
            var command=new MpcSignCommand(tenant,keyId,1,ChainType.APTOS,c.businessType(),c.businessReference(),c.signingSlot(),c.purpose(),c.payloadEncoding(),
                    HexFormat.of().formatHex(json.writeValueAsBytes(payload)),c.preimageHex());
            var changed=new SigningCandidate(original.signingRequestId(),original.approvalRequestIds(),command,original.execution(),null,null);
            if(fee.signum()>0)assertEquals(new java.math.BigInteger("200000"),verifyWire(reviewed,key,changed,NOW).maximumFeeAtomic());
            else assertThrows(IllegalArgumentException.class,()->verifyWire(reviewed,key,changed,NOW));
        }
    }
    @Test void amountRecipientMetadataNetworkAndFeeChangesFailEvenWhenTheCallerRecomputesTheNativeMessage() {
        var reviewed=reviewed(true);var u=unsigned(true);
        for(var changed:List.of(
                new MpcAptosTransactionCodec.Unsigned(u.sender(),1,"0x3",u.amountAtomic(),u.metadata(),1000,200,u.expiration(),2,PUBLIC),
                new MpcAptosTransactionCodec.Unsigned(u.sender(),1,u.recipient(),u.amountAtomic()+1,u.metadata(),1000,200,u.expiration(),2,PUBLIC),
                new MpcAptosTransactionCodec.Unsigned(u.sender(),1,u.recipient(),u.amountAtomic(),"0xbeef",1000,200,u.expiration(),2,PUBLIC),
                new MpcAptosTransactionCodec.Unsigned(u.sender(),1,u.recipient(),u.amountAtomic(),u.metadata(),1000,200,u.expiration(),1,PUBLIC),
                new MpcAptosTransactionCodec.Unsigned(u.sender(),1,u.recipient(),u.amountAtomic(),u.metadata(),10000,200,u.expiration(),2,PUBLIC),
                new MpcAptosTransactionCodec.Unsigned(u.sender(),1,u.recipient(),u.amountAtomic(),null,1000,200,u.expiration(),2,PUBLIC)))
            assertThrows(IllegalArgumentException.class,()->verify(reviewed,changed));
    }
    @Test void scriptOrOrderlessBytesCannotBeSubstitutedForTheWhitelistedFrameworkTransfer() {
        var reviewed=reviewed(false);var original=candidate(reviewed,unsigned(false));var c=original.command();
        for(String changed:List.of("42".repeat(32),c.preimageHex()+"00",c.preimageHex().substring(2))) {
            var command=new MpcSignCommand(tenant,keyId,1,ChainType.APTOS,c.businessType(),c.businessReference(),c.signingSlot(),c.purpose(),c.payloadEncoding(),c.payloadHex(),changed);
            var execution=new MpcClient.Request(UUID.randomUUID(),tenant,keyId,1,MpcClient.Operation.SIGN,MpcClient.Algorithm.ED25519,original.execution().online(),changed);
            assertThrows(IllegalArgumentException.class,()->verifyWire(reviewed,key,new SigningCandidate(original.signingRequestId(),original.approvalRequestIds(),command,execution,null,null),NOW));
        }
        assertThrows(IllegalArgumentException.class,()->new MpcAptosTransactionCodec.Unsigned(unsigned(false).sender(),-1,"0x2",1,null,1000,200,NOW.plusSeconds(600).getEpochSecond(),2,PUBLIC));
    }
    @Test void expiredAndUnreasonablyLongLivedTransactionsAreNotAuthorized() {
        var reviewed=reviewed(false);var u=unsigned(false);
        for(long expiry:List.of(NOW.getEpochSecond(),NOW.plusSeconds(3601).getEpochSecond()))
            assertThrows(IllegalArgumentException.class,()->verify(reviewed,new MpcAptosTransactionCodec.Unsigned(u.sender(),u.sequence(),u.recipient(),u.amountAtomic(),u.metadata(),u.maxGasAmount(),u.gasUnitPrice(),expiry,u.chainId(),PUBLIC)));
    }
    @Test void unrecognizedPayloadFieldsAndCrossTenantKeysCannotBecomeSigningAuthority() {
        var reviewed=reviewed(false);var original=candidate(reviewed,unsigned(false));var c=original.command();
        var body=json.readTree(HexFormat.of().parseHex(c.payloadHex())).asObject();body.put("script","arbitrary Move payload");
        var changed=new MpcSignCommand(tenant,keyId,1,ChainType.APTOS,c.businessType(),c.businessReference(),c.signingSlot(),c.purpose(),c.payloadEncoding(),HexFormat.of().formatHex(json.writeValueAsBytes(body)),c.preimageHex());
        assertThrows(RuntimeException.class,()->verifyWire(reviewed,key,new SigningCandidate(original.signingRequestId(),original.approvalRequestIds(),changed,original.execution(),null,null),NOW));
        var other=new RegisteredMpcKey(UUID.randomUUID(),keyId,"APTOS","ED25519",1,PUBLIC,null,"ACTIVE");
        assertThrows(IllegalArgumentException.class,()->verifyWire(reviewed,other,original,NOW));
    }
}
