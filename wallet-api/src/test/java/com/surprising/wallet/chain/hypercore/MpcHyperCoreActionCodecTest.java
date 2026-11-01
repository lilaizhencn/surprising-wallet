package com.surprising.wallet.chain.hypercore;

import com.surprising.wallet.sdk.signing.hypercore.MpcHyperCoreActionCodec;

import java.math.BigDecimal;
import java.util.HexFormat;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class MpcHyperCoreActionCodecTest {
    private static final String DESTINATION="0x0000000000000000000000000000000000000001";
    private static final String TOKEN="HYPE:0x0d01dc56dcaaca66ad901c959b4011ec";
    private final ECKey key=new ECKey();
    private final String owner="0x"+Keys.getAddress(Sign.publicFromPoint(key.decompress().getPubKey()));
    /** Vectors independently generated with eth-account 0.13.7 and the official Python SDK's typed fields. */
    @ParameterizedTest @CsvSource({
            "false,false,f7ded31529904e5bde8918528f0a96a515a341c836bec1849a08139b90954a42",
            "false,true,bdb544463a10f93b9a75012adf8ef85c56ff878d74030651e3af8352ad909a9b",
            "true,false,82a15e448e28e5628c7b0f65c7db5c1bbae58ed797f8d99ea14709432991cccf",
            "true,true,c7927171470e4f35bae0d64cb1e9b10db75179796b9659d803e6be9cf58ba8cf"})
    void digestMatchesIndependentReferenceAndExternalSignatureRecoversJointOwner(boolean mainnet,boolean spot,String expected) throws Exception {
        var unsigned=MpcHyperCoreActionCodec.prepare(owner,DESTINATION,new BigDecimal("1.250"),spot?TOKEN:null,1710000000000L,mainnet);
        byte[] digest=MpcHyperCoreActionCodec.digest(unsigned);assertEquals(expected,HexFormat.of().formatHex(digest));
        byte[] der=key.sign(Sha256Hash.wrap(digest)).encodeToDER();var body=MpcHyperCoreActionCodec.assemble(unsigned,key.getPubKey(),der);
        var signature=body.path("signature");
        var wire=new Sign.SignatureData((byte)signature.path("v").asInt(),Numeric.hexStringToByteArray(signature.path("r").asText()),Numeric.hexStringToByteArray(signature.path("s").asText()));
        assertEquals(owner,"0x"+Keys.getAddress(Sign.signedMessageHashToKey(digest,wire)));
        assertEquals(1710000000000L,body.path("nonce").asLong());assertEquals(unsigned.actionJson(),body.path("action").toString());
        assertThrows(IllegalArgumentException.class,()->MpcHyperCoreActionCodec.assemble(unsigned,new ECKey().getPubKey(),der));
        var changed=MpcHyperCoreActionCodec.prepare(owner,DESTINATION,new BigDecimal("1.26"),spot?TOKEN:null,1710000000000L,mainnet);
        assertThrows(IllegalArgumentException.class,()->MpcHyperCoreActionCodec.assemble(changed,key.getPubKey(),der));
    }
    @Test void malformedFieldsAndUnboundSigningDomainsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->MpcHyperCoreActionCodec.prepare(owner,DESTINATION,BigDecimal.ZERO,null,1,false));
        assertThrows(IllegalArgumentException.class,()->MpcHyperCoreActionCodec.prepare(owner,DESTINATION,BigDecimal.ONE,"HYPE",1,false));
        assertThrows(IllegalArgumentException.class,()->MpcHyperCoreActionCodec.prepare(owner,DESTINATION.toUpperCase(),BigDecimal.ONE,null,1,false));
        assertThrows(IllegalArgumentException.class,()->MpcHyperCoreActionCodec.prepare(owner,DESTINATION,BigDecimal.ONE,null,0,false));
        var good=MpcHyperCoreActionCodec.prepare(owner,DESTINATION,BigDecimal.ONE,null,1,false);
        var json=new ObjectMapper();var modified=json.readTree(good.actionJson());
        ((tools.jackson.databind.node.ObjectNode)modified).put("vaultAddress",owner);
        assertThrows(IllegalArgumentException.class,()->MpcHyperCoreActionCodec.digest(new MpcHyperCoreActionCodec.Unsigned(owner,modified.toString())));
        var amount=(tools.jackson.databind.node.ObjectNode)json.readTree(good.actionJson());amount.put("amount","1.00");
        assertThrows(IllegalArgumentException.class,()->MpcHyperCoreActionCodec.digest(new MpcHyperCoreActionCodec.Unsigned(owner,amount.toString())));
        var domain=(tools.jackson.databind.node.ObjectNode)json.readTree(good.actionJson());domain.put("signatureChainId","0x1");
        assertThrows(IllegalArgumentException.class,()->MpcHyperCoreActionCodec.digest(new MpcHyperCoreActionCodec.Unsigned(owner,domain.toString())));
    }
}
