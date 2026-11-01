package com.surprising.wallet.approval;

import com.google.common.primitives.UnsignedInteger;
import com.surprising.wallet.approval.chain.ApprovalWithdrawalVerifiers;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.MoneroPublicAddressCodec;
import com.surprising.wallet.sdk.signing.hypercore.MpcHyperCoreActionCodec;
import com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec;
import com.surprising.wallet.sdk.signing.solana.MpcSolanaTransactionCodec;
import com.surprising.wallet.sdk.signing.ton.MpcTonTransactionCodec;
import com.surprising.wallet.sdk.signing.xrp.MpcXrpTransactionCodec;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.bitcoinj.base.Base58;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.p2p.solanaj.core.PublicKey;
import org.ton.ton4j.cell.CellBuilder;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.xrpl.xrpl4j.model.transactions.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Actual SDK messages with explicit independent-fact fixtures. This tests policy semantics, not live RPC/broadcast. */
class AccountChainWithdrawalVerifiersTest {
    private static final String ED="d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String OTHER_ED="3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    private static final String SECP="0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
    private static final String REFERENCE="CW-fixed";
    private final UUID tenant=UUID.randomUUID(),keyId=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-11-02T00:00:00Z");private final ObjectMapper json=new ObjectMapper();
    private final ApprovalWithdrawalVerifiers verifiers=new ApprovalWithdrawalVerifiers(json,mock(ApprovalChainRpcClient.class));

    @ParameterizedTest @ValueSource(booleans={false,true})
    void solNativeAndSplRequireTheWholeMessageAllOwnedSignersMemoAndLiveReference(boolean token) {
        var fixture=solana(token,REFERENCE);check(fixture,false);
        assertThrows(IllegalArgumentException.class,()->verify(solana(token,"CW-other")));
        var stale=facts(fixture);stale.withObject("validBlockhash").put("value",false);
        assertThrows(IllegalArgumentException.class,()->verify(fixture,evidence(fixture.review(),stale)));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void tonNativeAndJettonRequireBusinessMemoQueryIdentityMasterOwnershipAndFeeBound(boolean token) {
        var fixture=ton(token,REFERENCE);check(fixture,true);
        assertThrows(IllegalArgumentException.class,()->verify(ton(token,"CW-other")));
        var stale=facts(fixture);stale.withObject("wallet").put("seqno",9);
        assertThrows(IllegalArgumentException.class,()->verify(fixture,evidence(fixture.review(),stale)));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void xrpNativeAndIssuedCurrencyMatchCanonicalDirectPaymentAndLedgerDomain(boolean token) {
        var fixture=xrp(token);check(fixture,true);
        var stale=facts(fixture);stale.withObject("server").withObject("validated_ledger").put("seq",2000);
        assertThrows(IllegalArgumentException.class,()->verify(fixture,evidence(fixture.review(),stale)));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void nearNativeAndNep141MatchBorshNonceReferenceFullAccessAndBusinessMemo(boolean token) {
        var fixture=near(token,REFERENCE);check(fixture,true);
        if(token)assertThrows(IllegalArgumentException.class,()->verify(near(true,"CW-other")));
        var restricted=facts(fixture);restricted.withObject("accessKey").put("permission","FunctionCall");
        assertThrows(IllegalArgumentException.class,()->verify(fixture,evidence(fixture.review(),restricted)));
    }
    @Test void hyperCoreOnlyAuthorizesTheExactActivatedSpotPocketAssetAndSignedNetwork() {
        String wireToken="USDC:0x"+"12".repeat(16);var fixture=hyperCore(wireToken);check(fixture,false);
        var inactive=facts(fixture);inactive.withObject("recipientRole").put("role","missing");
        assertThrows(IllegalArgumentException.class,()->verify(fixture,evidence(fixture.review(),inactive)));
        var changed=hyperCore("USDC:0x"+"34".repeat(16));
        assertThrows(IllegalArgumentException.class,()->verify(new Fixture(fixture.review(),changed.key(),changed.candidate(),fixture.evidence(),changed.sender())));
    }
    @Test void moneroNativePolicyBindsPublicSubaddressNetworkAmountAndMaximumFeeWithoutFreshSessionRecovery() {
        String publicKey=ED+OTHER_ED,sender=MoneroPublicAddressCodec.encode(HexFormat.of().parseHex(publicKey),"regtest",true);
        String recipient=MoneroPublicAddressCodec.encode(HexFormat.of().parseHex(OTHER_ED+ED),"regtest",true);
        var review=review("XMR","regtest","XMR","XMR","monero:"+"11".repeat(32),null,12,12,recipient);
        var key=key("XMR","MONERO_NATIVE_2_OF_3",publicKey,sender);
        var payload=new MoneroSigningPayload("regtest","11".repeat(32),sender,recipient,BigDecimal.ONE,BigDecimal.ZERO,BigInteger.valueOf(3_000_000_000L),3);
        check(new Fixture(review,key,candidate(review,key,"MONERO_NATIVE_PAYMENT_JSON",payload,HexFormat.of().parseHex(payload.preimageHex(json))),null,sender),false);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void suiNativeAndTokenRequireEveryLiveObjectOwnerVersionDigestAndType(boolean token) throws Exception {
        var f=sui(token);check(f,true);assertEquals(token?2:1,verify(f).inputs().size());
        var altered=facts(f);var objects=(tools.jackson.databind.node.ArrayNode)altered.path("objects");
        var object=sui.rpc.v2.ObjectOuterClass.Object.parseFrom(Base64.getDecoder().decode(objects.path(0).asText()));
        var changes=List.of(object.toBuilder().setOwner(object.getOwner().toBuilder().setAddress("0x"+"ff".repeat(32))).build(),
                object.toBuilder().setVersion(object.getVersion()+1).build(),object.toBuilder().setDigest(Base58.encode(HexFormat.of().parseHex("66".repeat(32)))).build(),
                object.toBuilder().setBalance(object.getBalance()+1).build(),object.toBuilder().setObjectType("0x2::coin::Coin<0x7::other::OTHER>").build());
        for(var changed:changes) {
            objects.set(0,json.getNodeFactory().stringNode(encode(changed)));
            assertThrows(IllegalArgumentException.class,()->verify(f,evidence(f.review(),altered)));
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void assetHubNativeAndTokenRequireIndependentRuntimeIndicesExtensionOrderAndFeeQuote(boolean token) {
        var f=polkadot(token);check(f,true);var altered=facts(f);altered.withObject("callIndices").put("keepAlive","0x0b00");
        assertThrows(IllegalArgumentException.class,()->verify(f,evidence(f.review(),altered)));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void cardanoNativeAndTokenRequireOriginalLiveOutputCborConservationValidityAndNativeExpense(boolean token) throws Exception {
        var f=cardano(token);check(f,true);assertEquals(1,verify(f).inputs().size());assertEquals(BigInteger.valueOf(token?2_200_000:200_000),verify(f).maximumFeeAtomic());
        var altered=facts(f);var outputs=utxorpc.v1beta.query.Query.ReadUtxosResponse.parseFrom(Base64.getDecoder().decode(altered.path("utxos").asText()));
        var changed=outputs.toBuilder().setItems(0,outputs.getItems(0).toBuilder().setNativeBytes(com.google.protobuf.ByteString.copyFrom(new byte[]{0}))).build();altered.put("utxos",encode(changed));
        assertThrows(IllegalArgumentException.class,()->verify(f,evidence(f.review(),altered)));
    }
    private void check(Fixture f,boolean recovery) {
        var verified=verify(f);assertEquals(recovery,verified.supportsExactRecovery());assertTrue(verified.maximumFeeAtomic().signum()>=0);
        var c=f.candidate().command();String changed="42".repeat(32);
        var command=new MpcSignCommand(c.tenantId(),c.keyId(),c.keyEpoch(),c.chain(),c.businessType(),c.businessReference(),c.signingSlot(),c.purpose(),c.payloadEncoding(),c.payloadHex(),changed);
        var e=f.candidate().execution();var execution=new MpcClient.Request(UUID.randomUUID(),e.tenantId(),e.keyId(),e.epoch(),e.operation(),e.algorithm(),e.online(),changed);
        var badMessage=new SigningCandidate(f.candidate().signingRequestId(),f.candidate().approvalRequestIds(),command,execution,null,null);
        assertThrows(IllegalArgumentException.class,()->verifiers.verifyBatch(List.of(f.review()),f.key(),Map.of(keyId,f.key()),badMessage,now,f.evidence()));
        var original=f.review().intent();var changedIntent=new WithdrawalIntent(original.id(),tenant,REFERENCE,original.chain(),original.network(),original.assetSymbol(),f.sender(),original.amount(),original.maximumNetworkFee(),original.source(),original.initiatorId(),original.subject(),original.externalReference(),original.fundingSource());
        var other=new ApprovalRequest(changedIntent,f.review().assetSnapshot().bind(changedIntent,json),f.review().assetSnapshot(),f.review().policyId(),1,List.of("ALL_WITHDRAWALS"),1,"APPROVED",false,now,now.plusSeconds(3600));
        assertThrows(IllegalArgumentException.class,()->verifiers.verifyBatch(List.of(other),f.key(),Map.of(keyId,f.key()),f.candidate(),now,f.evidence()));
        var foreign=new RegisteredMpcKey(UUID.randomUUID(),keyId,f.key().keyGroup(),f.key().algorithm(),1,f.key().publicKeyHex(),f.key().publicAddress(),"ACTIVE");
        assertThrows(IllegalArgumentException.class,()->verifiers.verifyBatch(List.of(f.review()),foreign,Map.of(keyId,foreign),f.candidate(),now,f.evidence()));
    }
    private Fixture solana(boolean token,String memoReference) {
        String sender=new PublicKey(HexFormat.of().parseHex(ED)).toBase58(),recipient=new PublicKey(HexFormat.of().parseHex(OTHER_ED)).toBase58();
        String mint=token?new PublicKey(new byte[32]).toBase58():null;
        var review=review("SOLANA","devnet",token?"USDC":"SOL","SOL","solana:"+new PublicKey(new byte[32]).toBase58(),mint,token?6:9,9,recipient);var key=key("SOLANA","ED25519",ED,null);
        String memo=HexFormat.of().formatHex(org.bitcoinj.base.Sha256Hash.hash(("surprising-wallet:solana:v1\n"+tenant+"\nWITHDRAWAL\n"+memoReference).getBytes(StandardCharsets.UTF_8)));
        var unsigned=MpcSolanaTransactionCodec.payment(sender,sender,recipient,mint,BigInteger.TEN.pow(token?6:9).longValueExact(),token?6:9,new PublicKey(new byte[32]).toBase58(),memo);
        var payload=new SolanaSigningPayload(review.assetSnapshot().symbol(),BigDecimal.ONE,BigDecimal.ZERO,token?6:9,mint,sender,recipient,sender,1100,unsigned);
        var facts=json.createObjectNode().put("genesis",new PublicKey(new byte[32]).toBase58()).put("blockHeight",1000).put("rent",2_039_280).put("feePayer",sender);
        facts.putObject("validBlockhash").put("value",true);facts.putObject("fee").put("value",5000);
        return new Fixture(review,key,candidate(review,key,"SOLANA_TX_JSON",payload,MpcSolanaTransactionCodec.signingBytes(unsigned)),evidence(review,facts),sender);
    }
    private Fixture ton(boolean token,String memoReference) {
        String sender=MpcTonTransactionCodec.canonicalAddress(MpcTonTransactionCodec.address("testnet",HexFormat.of().parseHex(ED))),recipient="0:"+"11".repeat(32),master=token?"0:"+"22".repeat(32):null;
        var review=review("TON","testnet",token?"USDT":"TON","TON","ton:"+"33".repeat(32),master,token?6:9,9,recipient);var key=key("TON","ED25519",ED,null);
        String sourceWallet=token?"0:"+"44".repeat(32):null,targetWallet=token?"0:"+"55".repeat(32):null;
        var unsigned=new MpcTonTransactionCodec.Unsigned("testnet",sender,recipient,BigInteger.TEN.pow(token?6:9),master,sourceWallet,targetWallet,7,now.plusSeconds(600).getEpochSecond(),
                MpcTonTransactionCodec.businessQueryId(tenant,"WITHDRAWAL",memoReference,token),MpcTonTransactionCodec.businessMemo(tenant,"WITHDRAWAL",memoReference),ED);
        var payload=new TonSigningPayload(review.assetSnapshot().symbol(),BigDecimal.ONE,BigDecimal.ZERO,token?6:9,sender,unsigned);var facts=json.createObjectNode();
        facts.putObject("masterchain").putObject("init").put("root_hash","33".repeat(32));facts.putObject("wallet").put("seqno",7);
        var fee=facts.putObject("fee").putObject("source_fees");for(String name:List.of("in_fwd_fee","storage_fee","gas_fee","fwd_fee"))fee.put(name,100);
        if(token) {
            facts.put("sourceJettonWallet",sourceWallet).put("recipientJettonWallet",targetWallet);
            var data=facts.putObject("jettonData").put("exit_code",0).putArray("stack");data.addArray().add("num").add("0x1");
            data.add(addressStack(sender));data.add(addressStack(master));data.addArray().add("cell").addObject().put("bytes","");
        }
        return new Fixture(review,key,candidate(review,key,"TON_V4R2_PAYMENT_JSON",payload,MpcTonTransactionCodec.signingBytes(unsigned)),evidence(review,facts),sender);
    }
    private JsonNode addressStack(String address) {
        var cell=CellBuilder.beginCell().storeAddress(org.ton.ton4j.address.Address.of(address)).endCell();
        var stack=json.createArrayNode().add("cell");stack.addObject().put("bytes",Base64.getEncoder().encodeToString(cell.toBoc(false)));return stack;
    }
    private Fixture xrp(boolean token) {
        var pub=org.xrpl.xrpl4j.crypto.keys.PublicKey.fromBase16EncodedPublicKey(SECP);String sender=pub.deriveAddress().value();
        String recipient=org.xrpl.xrpl4j.crypto.keys.PublicKey.fromBase16EncodedPublicKey(HexFormat.of().formatHex(new ECKey().getPubKey())).deriveAddress().value();
        var review=review("XRP","testnet",token?"USD":"XRP","XRP","xrpl:1",token?recipient+":USD":null,6,6,recipient);var key=key("XRP","ECDSA_SECP256K1",SECP,null);
        CurrencyAmount amount=token?IssuedCurrencyAmount.builder().issuer(Address.of(recipient)).currency("USD").value("1").build():XrpCurrencyAmount.ofDrops(1_000_000);
        var payment=Payment.builder().account(pub.deriveAddress()).destination(Address.of(recipient)).amount(amount).fee(XrpCurrencyAmount.ofDrops(12))
                .sequence(UnsignedInteger.valueOf(7)).lastLedgerSequence(UnsignedInteger.valueOf(1020)).signingPublicKey(pub).build();
        var unsigned=MpcXrpTransactionCodec.prepare(payment);var payload=new XrpSigningPayload(review.assetSnapshot().symbol(),BigDecimal.ONE,BigDecimal.ZERO,unsigned);
        var facts=json.createObjectNode();facts.putObject("server").put("network_id",1).putObject("validated_ledger").put("seq",1000);
        facts.putObject("account").putObject("account_data").put("Account",sender).put("Sequence",7);
        return new Fixture(review,key,candidate(review,key,"XRPL_TX_JSON",payload,MpcXrpTransactionCodec.digest(unsigned)),evidence(review,facts),sender);
    }
    private Fixture near(boolean token,String memoReference) {
        String sender=MpcNearTransactionCodec.address(HexFormat.of().parseHex(ED)),recipient="recipient.testnet",genesis=Base58.encode(new byte[32]),block=Base58.encode(HexFormat.of().parseHex("11".repeat(32)));
        var review=review("NEAR","testnet",token?"USDC":"NEAR","NEAR","near:"+genesis,token?"usdc.testnet":null,token?6:24,24,recipient);var key=key("NEAR","ED25519",ED,null);
        var unsigned=new MpcNearTransactionCodec.Unsigned("testnet",genesis,sender,7,recipient,BigInteger.TEN.pow(token?6:24),token?"usdc.testnet":null,token?100_000_000_000_000L:0,token?memoReference:null,1000,1100,block,ED);
        var payload=new NearSigningPayload(review.assetSnapshot().symbol(),BigDecimal.ONE,BigDecimal.ZERO,token?6:24,unsigned);var facts=json.createObjectNode();
        facts.putObject("genesis").putObject("header").put("hash",genesis);facts.putObject("reference").putObject("header").put("hash",block).put("height",1000);
        facts.putObject("final").putObject("header").put("height",1001);facts.putObject("accessKey").put("permission","FullAccess").put("nonce",6);
        facts.put("maximumGasPrice","100000000");facts.putObject("gasPrice").put("gas_price","100000000");
        return new Fixture(review,key,candidate(review,key,"NEAR_BORSH_PAYMENT_JSON",payload,MpcNearTransactionCodec.signingBytes(unsigned)),evidence(review,facts),sender);
    }
    @Test void nearRegistrationRequiresExactMissingMinimumAndOriginalParentPrincipal() {
        var base=near(true,REFERENCE);String reference="near-storage:"+SigningAuthorization.hash((tenant+":WITHDRAWAL:"+REFERENCE).getBytes(StandardCharsets.UTF_8));
        var review=systemReview(base.review(),reference,BigDecimal.ONE);var original=json.readValue(HexFormat.of().parseHex(base.candidate().command().payloadHex()),NearSigningPayload.class).unsigned();
        var registration=new MpcNearTransactionCodec.Registration("testnet",original.genesisHash(),original.sender(),7,original.recipient(),original.tokenContract(),BigInteger.TEN.pow(21),30_000_000_000_000L,1000,1100,original.blockHash(),ED);
        var payload=new NearRegistrationPayload(new NearOperation(tenant,REFERENCE,NearOperation.Kind.WITHDRAWAL),new NearPayment("USDC",original.recipient(),BigDecimal.ONE,BigDecimal.ZERO),1,original.sender(),6,BigInteger.TEN.pow(22),registration);
        var c=automaticCandidate(review,base.key(),MpcSignCommand.BusinessType.FUNDING,"storage-registration","NEAR_STORAGE_REGISTRATION_JSON",payload,MpcNearTransactionCodec.signingBytes(registration));
        var facts=facts(base);facts.putNull("storageBalance");facts.putObject("storageBounds").put("min",registration.depositAtomic().toString()).putNull("max");facts.put("parentBalance","1000000");
        var f=new Fixture(review,base.key(),c,evidence(review,facts),base.sender());assertTrue(verify(f).preparationVerified());
        var registered=facts.deepCopy();registered.putObject("storageBalance").put("total",registration.depositAtomic().toString());assertThrows(IllegalArgumentException.class,()->verify(f,evidence(review,registered)));
        var changed=facts.deepCopy();changed.withObject("storageBounds").put("min","1");assertThrows(IllegalArgumentException.class,()->verify(f,evidence(review,changed)));
        var empty=facts.deepCopy().put("parentBalance","0");assertThrows(IllegalArgumentException.class,()->verify(f,evidence(review,empty)));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void xrpSetupRequiresRealReserveDeficitOrExactNoRippleTrustLimit(boolean trust) {
        String targetHex=HexFormat.of().formatHex(new ECKey().getPubKey());var source=org.xrpl.xrpl4j.crypto.keys.PublicKey.fromBase16EncodedPublicKey(SECP);var target=org.xrpl.xrpl4j.crypto.keys.PublicKey.fromBase16EncodedPublicKey(targetHex);
        var issuer=org.xrpl.xrpl4j.crypto.keys.PublicKey.fromBase16EncodedPublicKey(HexFormat.of().formatHex(new ECKey().getPubKey()));String address=target.deriveAddress().value(),gasAddress=source.deriveAddress().value();
        UUID targetId=trust?keyId:UUID.randomUUID();String signingHex=trust?targetHex:SECP;
        var signingKey=key("XRP","ECDSA_SECP256K1",signingHex,null);var targetKey=new RegisteredMpcKey(tenant,targetId,"XRP","ECDSA_SECP256K1",1,targetHex,null,"ACTIVE");
        var base=review("XRP","testnet","USD","XRP","xrpl:1",issuer.deriveAddress().value()+":USD",6,6,address,new BigDecimal("2"));
        String reference="prepare:"+targetId+":USD:"+(trust?"trust":"fund");var review=systemReview(base,reference,BigDecimal.ZERO);
        Transaction tx=trust?TrustSet.builder().account(target.deriveAddress()).limitAmount(IssuedCurrencyAmount.builder().issuer(issuer.deriveAddress()).currency("USD").value("1000").build())
                .flags(org.xrpl.xrpl4j.model.flags.TrustSetFlags.builder().tfSetNoRipple().build()).fee(XrpCurrencyAmount.ofDrops(12)).sequence(UnsignedInteger.valueOf(7)).lastLedgerSequence(UnsignedInteger.valueOf(1020)).signingPublicKey(target).build()
                :Payment.builder().account(source.deriveAddress()).destination(target.deriveAddress()).amount(XrpCurrencyAmount.ofDrops(1_000_000)).fee(XrpCurrencyAmount.ofDrops(12)).sequence(UnsignedInteger.valueOf(7)).lastLedgerSequence(UnsignedInteger.valueOf(1020)).signingPublicKey(source).build();
        var unsigned=MpcXrpTransactionCodec.prepare(tx);var c=automaticCandidate(review,signingKey,MpcSignCommand.BusinessType.ADDRESS_PREPARATION,"transaction","XRPL_TX_JSON",new XrpSigningPayload("USD",trust?BigDecimal.ZERO:BigDecimal.ONE,BigDecimal.ZERO,unsigned),MpcXrpTransactionCodec.digest(unsigned));
        var facts=json.createObjectNode().put("gasAddress",gasAddress).put("trustLimit","1000");facts.putObject("server").put("network_id",1).putObject("validated_ledger").put("seq",1000).put("reserve_base_xrp","1").put("reserve_inc_xrp","0.2");
        facts.putObject("source").putObject("account_data").put("Account",trust?address:gasAddress).put("Sequence",7).put("Balance","100000000").put("OwnerCount",0);
        facts.putObject("issuer").putObject("account_data").put("Account",issuer.deriveAddress().value()).put("Flags",0);
        if(trust){facts.putObject("target").putObject("account_data").put("Account",address).put("Balance","100000000").put("OwnerCount",0);facts.putObject("lines").put("account",address).putArray("lines");}
        else facts.putObject("target").put("error","actNotFound");
        var keys=new HashMap<UUID,RegisteredMpcKey>();keys.put(keyId,signingKey);keys.put(targetId,targetKey);
        assertTrue(verifiers.verifyBatch(List.of(review),signingKey,keys,c,now,evidence(review,facts)).preparationVerified());
        var altered=facts.deepCopy();if(trust)altered.put("trustLimit","2000");else {altered.remove("target");altered.putObject("target").putObject("account_data").put("Account",address).put("Balance","100000000").put("OwnerCount",0);altered.putObject("lines").put("account",address).putArray("lines");}
        assertThrows(IllegalArgumentException.class,()->verifiers.verifyBatch(List.of(review),signingKey,keys,c,now,evidence(review,altered)));
    }
    private ApprovalRequest systemReview(ApprovalRequest original,String reference,BigDecimal amount) {
        var i=original.intent();var intent=new WithdrawalIntent(i.id(),tenant,reference,i.chain(),i.network(),i.assetSymbol(),i.recipient(),amount,i.maximumNetworkFee(),"SYSTEM",UUID.randomUUID(),"AUTOMATION:fixture","","ADDRESS");
        return new ApprovalRequest(intent,original.assetSnapshot().bind(intent,json),original.assetSnapshot(),UUID.randomUUID(),1,List.of("INDEPENDENT_OPERATING_RULE"),0,"APPROVED",false,now,now.plusSeconds(3600));
    }
    private SigningCandidate automaticCandidate(ApprovalRequest review,RegisteredMpcKey key,MpcSignCommand.BusinessType type,String slot,String encoding,Object payload,byte[] bytes) {
        var original=candidate(review,key,encoding,payload,bytes);var c=original.command();var command=new MpcSignCommand(tenant,keyId,1,c.chain(),type,review.intent().businessReference(),slot,c.purpose(),encoding,c.payloadHex(),c.preimageHex());
        return new SigningCandidate(original.signingRequestId(),List.of(),command,original.execution(),null,null);
    }
    private Fixture hyperCore(String token) {
        String sender="0x"+Keys.getAddress(Sign.publicFromPoint(ECKey.fromPublicOnly(HexFormat.of().parseHex(SECP)).decompress().getPubKey())),recipient="0x"+"11".repeat(20);
        var review=review("HYPERCORE","testnet","USDC","USDC","hyperliquid:Testnet",token,6,6,recipient);var key=key("HYPERCORE","ECDSA_SECP256K1",SECP,null);
        var unsigned=MpcHyperCoreActionCodec.prepare(sender,recipient,BigDecimal.ONE,token,now.toEpochMilli(),false);var payload=new HyperCoreSigningPayload("USDC",BigDecimal.ONE,BigDecimal.ZERO,unsigned);
        var facts=json.createObjectNode();facts.putObject("recipientRole").put("role","user");
        return new Fixture(review,key,candidate(review,key,"HYPERCORE_JSON",payload,MpcHyperCoreActionCodec.digest(unsigned)),evidence(review,facts),sender);
    }
    private Fixture sui(boolean token) {
        String sender=com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec.address(HexFormat.of().parseHex(ED)),recipient="0x"+"11".repeat(32),genesis=Base58.encode(new byte[32]);
        String coinType=token?"0x3::usdc::USDC":"0x2::sui::SUI";
        var review=review("SUI","localnet",token?"USDC":"SUI","SUI","sui:"+genesis,token?coinType:null,token?6:9,9,recipient);var key=key("SUI","ED25519",ED,null);
        var gas=new com.surprising.wallet.sdk.signing.sui.SuiCoin("0x"+"22".repeat(32),"7",Base58.encode(HexFormat.of().parseHex("33".repeat(32))),new BigDecimal("10000000000"));
        var coin=new com.surprising.wallet.sdk.signing.sui.SuiCoin("0x"+"44".repeat(32),"8",Base58.encode(HexFormat.of().parseHex("55".repeat(32))),new BigDecimal("2000000"));
        var unsigned=new com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec.Unsigned("localnet",genesis,sender,recipient,token?1_000_000:1_000_000_000,coinType,token?List.of(coin):List.of(),List.of(gas),1000,1_000_000,ED);
        var payload=new SuiSigningPayload(review.assetSnapshot().symbol(),BigDecimal.ONE,BigDecimal.ZERO,token?6:9,unsigned);var facts=json.createObjectNode().put("chainId",genesis).put("gasPrice",1000);
        var objects=facts.putArray("objects");
        for(var item:java.util.stream.Stream.concat(unsigned.inputs().stream(),unsigned.gasPayment().stream()).toList()) {
            String type=item.objectId().equals(gas.objectId())?"0x2::sui::SUI":coinType;
            var object=sui.rpc.v2.ObjectOuterClass.Object.newBuilder().setObjectId(item.objectId()).setVersion(Long.parseUnsignedLong(item.version())).setDigest(item.digest())
                    .setObjectType("0x2::coin::Coin<"+type+">").setBalance(item.balance().longValueExact()).setOwner(sui.rpc.v2.OwnerOuterClass.Owner.newBuilder().setKind(sui.rpc.v2.OwnerOuterClass.Owner.OwnerKind.ADDRESS).setAddress(sender)).build();objects.add(encode(object));
        }
        return new Fixture(review,key,candidate(review,key,"SUI_PTB_PAYMENT_JSON",payload,com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec.signingBytes(unsigned)),evidence(review,facts),sender);
    }
    private Fixture polkadot(boolean token) {
        String sender=com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec.address(HexFormat.of().parseHex(ED),42),recipient=com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec.address(HexFormat.of().parseHex(OTHER_ED),42);
        var review=review("DOT","westend",token?"USDC":"DOT","DOT","polkadot:"+"11".repeat(32),token?"1984":null,token?6:12,12,recipient);var key=key("DOT","ED25519",ED,null);
        var extensions=List.of("CheckNonZeroSender","CheckSpecVersion","CheckTxVersion","CheckGenesis","CheckMortality","CheckNonce","CheckWeight","ChargeTransactionPayment");
        var indices=new com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec.CallIndices("0x0a00","0x0a03");
        var unsigned=new com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec.Unsigned("westend",com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec.Role.ASSET_HUB,"0x"+"11".repeat(32),1000,1,sender,ED,recipient,BigInteger.TEN.pow(token?6:12),token?"1984":null,7,64,1000,"0x"+"22".repeat(32),extensions,indices,true);
        var payload=new PolkadotSigningPayload(review.assetSnapshot().symbol(),BigDecimal.ONE,BigDecimal.ZERO,token?6:12,12,BigInteger.valueOf(1_000_000_000),BigInteger.valueOf(1000),unsigned);
        var facts=json.createObjectNode().put("genesisHash",unsigned.genesisHash()).put("blockHash",unsigned.blockHash()).put("blockHeight",1000).put("finalizedHeight",1001).put("nonce",7).put("specVersion",1000).put("transactionVersion",1)
                .put("decimals",token?6:12).put("nativeDecimals",12).put("extrinsicVersion",4).put("extensionVersion",0).put("existentialDeposit",1000).put("feePlanck",100);
        facts.set("callIndices",json.valueToTree(indices));facts.set("extensions",json.valueToTree(extensions));
        return new Fixture(review,key,candidate(review,key,"POLKADOT_SCALE_PAYMENT_JSON",payload,com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec.signingBytes(unsigned)),evidence(review,facts),sender);
    }
    private Fixture cardano(boolean token) {
        String sender=com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec.address(HexFormat.of().parseHex(ED),false),recipient=com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec.address(HexFormat.of().parseHex(OTHER_ED),false);
        String unit=token?"12".repeat(28)+"55534443":"lovelace";var review=review("ADA","preview",token?"USDC":"ADA","ADA","cardano:"+"01".repeat(32)+":42",token?unit:null,6,6,recipient,BigDecimal.TEN);var key=key("ADA","ED25519",ED,null);
        var originalOutput=cardanoOutput(sender,100_000_000,token?2_000_000:0);long deliveredAda=token?2_000_000:1_000_000;
        var inputs=array(array(cborBytes(HexFormat.of().parseHex("02".repeat(32))),uint(0)));inputs.setTag(258);
        var outputs=array(cardanoOutput(recipient,deliveredAda,token?1_000_000:0),cardanoOutput(sender,100_000_000-deliveredAda-200_000,token?1_000_000:0));
        var body=new co.nstant.in.cbor.model.Map().put(uint(0),inputs).put(uint(1),outputs).put(uint(2),uint(200_000)).put(uint(3),uint(1420)).put(uint(8),uint(700)).put(uint(15),uint(0));
        String raw=HexFormat.of().formatHex(com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec.encode(array(body,new co.nstant.in.cbor.model.Map(),co.nstant.in.cbor.model.SimpleValue.TRUE,co.nstant.in.cbor.model.SimpleValue.NULL)));
        String originalHex=HexFormat.of().formatHex(com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec.encode(originalOutput));
        var unsigned=new com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec.Unsigned("01".repeat(32),42,sender,ED,recipient,unit,BigInteger.valueOf(1_000_000),raw,List.of(new com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec.Input("02".repeat(32),0,originalHex)));
        var payload=new CardanoSigningPayload(review.assetSnapshot().symbol(),BigDecimal.ONE,BigDecimal.ZERO,6,1,new com.surprising.wallet.common.chain.ChainScanPoint(300,700,"03".repeat(32)),unsigned);
        var genesis=utxorpc.v1beta.query.Query.ReadGenesisResponse.newBuilder().setGenesis(com.google.protobuf.ByteString.copyFrom(HexFormat.of().parseHex("01".repeat(32))))
                .setCardano(utxorpc.v1beta.cardano.Cardano.Genesis.newBuilder().setNetworkId("Testnet").setNetworkMagic(42)).build();
        var parameters=utxorpc.v1beta.query.Query.ReadParamsResponse.newBuilder().setValues(utxorpc.v1beta.query.Query.AnyChainParams.newBuilder().setCardano(utxorpc.v1beta.cardano.Cardano.PParams.newBuilder()
                .setMaxTxSize(16384).setMinFeeCoefficient(cardanoInteger(1)).setMinFeeConstant(cardanoInteger(0)).setProtocolVersion(utxorpc.v1beta.cardano.Cardano.ProtocolVersion.newBuilder().setMajor(10)))).build();
        var tip=utxorpc.v1beta.sync.Sync.ReadTipResponse.newBuilder().setTip(utxorpc.v1beta.sync.Sync.BlockRef.newBuilder().setHash(com.google.protobuf.ByteString.copyFrom(HexFormat.of().parseHex("03".repeat(32)))).setSlot(701)).build();
        var reference=utxorpc.v1beta.query.Query.TxoRef.newBuilder().setHash(com.google.protobuf.ByteString.copyFrom(HexFormat.of().parseHex("02".repeat(32)))).setIndex(0).build();
        var live=utxorpc.v1beta.query.Query.AnyUtxoData.newBuilder().setTxoRef(reference).setNativeBytes(com.google.protobuf.ByteString.copyFrom(HexFormat.of().parseHex(originalHex))).build();
        var utxos=utxorpc.v1beta.query.Query.ReadUtxosResponse.newBuilder().addItems(live).build();
        var facts=json.createObjectNode().put("genesis",encode(genesis)).put("parameters",encode(parameters)).put("tip",encode(tip)).put("utxos",encode(utxos));
        return new Fixture(review,key,candidate(review,key,"CARDANO_PAYMENT_JSON",payload,com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec.signingBytes(unsigned)),evidence(review,facts),sender);
    }
    private co.nstant.in.cbor.model.DataItem cardanoOutput(String address,long lovelace,long tokens) {
        co.nstant.in.cbor.model.DataItem value=uint(lovelace);
        if(tokens>0) {var assets=new co.nstant.in.cbor.model.Map().put(cborBytes(HexFormat.of().parseHex("12".repeat(28))),new co.nstant.in.cbor.model.Map().put(cborBytes(HexFormat.of().parseHex("55534443")),uint(tokens)));value=array(uint(lovelace),assets);}
        return array(cborBytes(com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec.addressBytes(address)),value);
    }
    private static co.nstant.in.cbor.model.Array array(co.nstant.in.cbor.model.DataItem... values){var array=new co.nstant.in.cbor.model.Array();for(var value:values)array.add(value);return array;}
    private static co.nstant.in.cbor.model.UnsignedInteger uint(long value){return new co.nstant.in.cbor.model.UnsignedInteger(value);}
    private static co.nstant.in.cbor.model.ByteString cborBytes(byte[] value){return new co.nstant.in.cbor.model.ByteString(value);}
    private static utxorpc.v1beta.cardano.Cardano.BigInt cardanoInteger(long value){return utxorpc.v1beta.cardano.Cardano.BigInt.newBuilder().setInt(value).build();}
    private static String encode(com.google.protobuf.MessageLite value){return Base64.getEncoder().encodeToString(value.toByteArray());}
    private ApprovalRequest review(String chain,String network,String symbol,String nativeSymbol,String identity,String token,int decimals,int nativeDecimals,String recipient) {
        return review(chain,network,symbol,nativeSymbol,identity,token,decimals,nativeDecimals,recipient,BigDecimal.ONE);
    }
    private ApprovalRequest review(String chain,String network,String symbol,String nativeSymbol,String identity,String token,int decimals,int nativeDecimals,String recipient,BigDecimal maximumFee) {
        var asset=new ApprovalAssetSnapshot(chain,network,symbol,nativeSymbol,identity,token,decimals,nativeDecimals,maximumFee);
        var intent=new WithdrawalIntent(UUID.randomUUID(),tenant,REFERENCE,chain,network,symbol,recipient,BigDecimal.ONE,maximumFee,"API",UUID.randomUUID(),"customer","merchant","ADDRESS");
        return new ApprovalRequest(intent,asset.bind(intent,json),asset,UUID.randomUUID(),1,List.of("ALL_WITHDRAWALS"),1,"APPROVED",false,now,now.plusSeconds(3600));
    }
    private RegisteredMpcKey key(String chain,String algorithm,String publicKey,String address){return new RegisteredMpcKey(tenant,keyId,chain,algorithm,1,publicKey,address,"ACTIVE");}
    private SigningCandidate candidate(ApprovalRequest review,RegisteredMpcKey key,String encoding,Object payload,byte[] message) {
        String preimage=HexFormat.of().formatHex(message);var command=new MpcSignCommand(tenant,keyId,1,ChainType.valueOf(key.keyGroup()),MpcSignCommand.BusinessType.WITHDRAWAL,REFERENCE,"transaction",MpcSignCommand.Purpose.TRANSACTION,encoding,HexFormat.of().formatHex(json.writeValueAsBytes(payload)),preimage);
        var execution=new MpcClient.Request(UUID.randomUUID(),tenant,keyId,1,MpcClient.Operation.SIGN,MpcClient.Algorithm.valueOf(key.algorithm()),List.of("a".repeat(64),"c".repeat(64)),preimage);
        return new SigningCandidate(UUID.randomUUID(),List.of(review.intent().id()),command,execution,null,null);
    }
    private JsonNode evidence(ApprovalRequest review,JsonNode facts){return json.createObjectNode().put("chain",review.assetSnapshot().chain()).put("networkIdentity",review.assetSnapshot().networkIdentity()).set("facts",facts);}
    private ObjectNode facts(Fixture f){return (ObjectNode)json.readTree(f.evidence().path("facts").toString());}
    private ApprovalWithdrawalVerifiers.Verified verify(Fixture f){return verify(f,f.evidence());}
    private ApprovalWithdrawalVerifiers.Verified verify(Fixture f,JsonNode evidence){return verifiers.verifyBatch(List.of(f.review()),f.key(),Map.of(keyId,f.key()),f.candidate(),now,evidence);}
    private record Fixture(ApprovalRequest review,RegisteredMpcKey key,SigningCandidate candidate,JsonNode evidence,String sender) { }
}
