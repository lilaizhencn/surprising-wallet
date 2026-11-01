package com.surprising.wallet.chain.polkadot;

import java.util.*;
import org.bitcoinj.base.Base58;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** Independent official SDK/actual metadata vectors; reference private keys were discarded. */
class MpcPolkadotTransactionCodecTest {
    private final ObjectMapper json=new ObjectMapper();private static final HexFormat HEX=HexFormat.of();
    private JsonNode fixtures()throws Exception{try(var input=getClass().getResourceAsStream("/mpc/polkadot-codec-reference.json")){return json.readTree(input).path("vectors");}}
    private MpcPolkadotTransactionCodec.Unsigned unsigned(JsonNode item){return json.treeToValue(item.path("unsigned"),MpcPolkadotTransactionCodec.Unsigned.class);}
    @Test void relayAndAssetHubMatchSdkCallPayloadEdWitnessWholeExtrinsicAndHash()throws Exception {
        var items=fixtures();assertEquals(5,items.size());
        for(var item:items){var u=unsigned(item);assertEquals(item.path("callHex").asText(),"0x"+HEX.formatHex(MpcPolkadotTransactionCodec.callBytes(u)));
            assertEquals(item.path("payloadHex").asText(),"0x"+HEX.formatHex(MpcPolkadotTransactionCodec.payloadBytes(u)));
            assertEquals(item.path("signingMessageHex").asText(),"0x"+HEX.formatHex(MpcPolkadotTransactionCodec.signingBytes(u)));
            var selected=MpcPolkadotTransactionCodec.assemble(u,HEX.parseHex(item.path("signatureHex").asText()));assertEquals(json.treeToValue(item.path("selected"),MpcPolkadotTransactionCodec.Signed.class),selected);
            MpcPolkadotTransactionCodec.verifySelected(u,selected);assertEquals(u.blockHeight()+64,u.validUntilHeight());}
    }
    @Test void liveMainnetV16PipelineZeroMatchesIndependentSdkWithoutSubmittingAnything()throws Exception {
        JsonNode items;try(var input=getClass().getResourceAsStream("/mpc/polkadot-mainnet-v4-reference.json")){items=json.readTree(input).path("vectors");}
        assertEquals(2,items.size());for(var item:items){var u=unsigned(item);assertEquals("mainnet",u.network());assertEquals(MpcPolkadotTransactionCodec.Role.ASSET_HUB,u.role());assertTrue(u.extensions().contains("PrevalidateAttests"));assertFalse(u.extensions().contains("VerifyMultiSignature"));
            assertEquals(item.path("payloadHex").asText(),"0x"+HEX.formatHex(MpcPolkadotTransactionCodec.payloadBytes(u)));
            var selected=MpcPolkadotTransactionCodec.assemble(u,HEX.parseHex(item.path("signatureHex").asText()));assertEquals(json.treeToValue(item.path("selected"),MpcPolkadotTransactionCodec.Signed.class),selected);MpcPolkadotTransactionCodec.verifySelected(u,selected);}
    }
    @Test void sourceNetworkReferenceNoncePrincipalAndKeepAliveCannotChangeAfterSigning()throws Exception {
        var item=fixtures().get(0);byte[] signature=HEX.parseHex(item.path("signatureHex").asText());
        for(String field:List.of("genesisHash","blockHash","nonce","amountAtomic","recipient","keepAlive","specVersion","transactionVersion")) {
            var changed=(ObjectNode)item.path("unsigned").deepCopy();
            switch(field){
                case "genesisHash","blockHash" -> changed.put(field,"0x"+"ab".repeat(32));
                case "nonce","specVersion","transactionVersion" -> changed.put(field,changed.path(field).asLong()+1);
                case "amountAtomic" -> changed.put(field,"1");
                case "recipient" -> changed.put(field,MpcPolkadotTransactionCodec.address(HEX.parseHex("ab".repeat(32)),42));
                case "keepAlive" -> changed.put(field,false);
                default -> throw new AssertionError(field);
            }
            assertThrows(RuntimeException.class,()->MpcPolkadotTransactionCodec.assemble(json.treeToValue(changed,MpcPolkadotTransactionCodec.Unsigned.class),signature),field);
        }
    }
    @Test void unknownOrMissingExtensionsAndOutOfRangeNonceCannotCreateAnIntent()throws Exception {
        var item=fixtures().get(0);var unknown=(ObjectNode)item.path("unsigned").deepCopy();unknown.withArray("extensions").add("UnreviewedAssetFee");assertThrows(RuntimeException.class,()->json.treeToValue(unknown,MpcPolkadotTransactionCodec.Unsigned.class));
        var missing=(ObjectNode)item.path("unsigned").deepCopy();missing.putArray("extensions").add("CheckNonce");assertThrows(RuntimeException.class,()->json.treeToValue(missing,MpcPolkadotTransactionCodec.Unsigned.class));
        var nonce=(ObjectNode)item.path("unsigned").deepCopy();nonce.put("nonce",4_294_967_296L);assertThrows(RuntimeException.class,()->json.treeToValue(nonce,MpcPolkadotTransactionCodec.Unsigned.class));
        var role=(ObjectNode)fixtures().get(3).path("unsigned").deepCopy();role.put("role","RELAY");assertThrows(RuntimeException.class,()->json.treeToValue(role,MpcPolkadotTransactionCodec.Unsigned.class));
    }
    @Test void changedWireSignatureOrTransactionHashCannotBeSelected()throws Exception {
        var item=fixtures().get(0);var u=unsigned(item);var selected=json.treeToValue(item.path("selected"),MpcPolkadotTransactionCodec.Signed.class);var wire=HEX.parseHex(selected.extrinsicHex().substring(2));wire[45]^=1;
        assertThrows(RuntimeException.class,()->MpcPolkadotTransactionCodec.verifySelected(u,new MpcPolkadotTransactionCodec.Signed("0x"+HEX.formatHex(wire),selected.txHash())));
        assertThrows(RuntimeException.class,()->MpcPolkadotTransactionCodec.verifySelected(u,new MpcPolkadotTransactionCodec.Signed(selected.extrinsicHex(),"0x"+"00".repeat(32))));
    }
    @Test void publicSs58EncodingAndChecksumsMatchIndependentSdkAcrossBothPrefixSizes()throws Exception {
        for(var item:fixtures()){byte[] key=HEX.parseHex(unsigned(item).publicKeyHex());for(var entry:item.path("ss58Addresses").properties()){
            int prefix=Integer.parseInt(entry.getKey());String address=MpcPolkadotTransactionCodec.address(key,prefix);assertEquals(entry.getValue().asText(),address);assertArrayEquals(key,MpcPolkadotTransactionCodec.account(address));
            byte[] broken=Base58.decode(address);broken[broken.length-1]^=1;assertThrows(RuntimeException.class,()->MpcPolkadotTransactionCodec.account(Base58.encode(broken)));}}
    }
}
