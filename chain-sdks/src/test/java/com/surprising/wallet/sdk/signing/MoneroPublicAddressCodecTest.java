package com.surprising.wallet.sdk.signing;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Independent encoding against actual official native RPC root/subaddress/integrated outputs, not a self-generated round trip. */
class MoneroPublicAddressCodecTest {
    private final HexFormat hex = HexFormat.of();
    private JsonNode vectors() throws Exception {
        try (var resource = getClass().getResourceAsStream("/signing/monero-public-address-reference.json")) {
            assertNotNull(resource); return new ObjectMapper().readTree(resource).path("vectors");
        }
    }
    @Test void officialNativePublicKeysReproduceExactRootAccountAndIntegratedAddresses() throws Exception {
        int checked = 0;
        for (var vector : vectors()) {
            String address = vector.path("address").asText(), network = vector.path("network").asText();
            byte[] keys = hex.parseHex(vector.path("publicKeyHex").asText());
            var decoded = MoneroPublicAddressCodec.decode(address);
            assertArrayEquals(keys, decoded.publicKeys());
            assertEquals(MoneroPublicAddressCodec.Network.MAINNET, decoded.network());
            assertEquals(vector.path("kind").asText().equals("ADDRESS"), decoded.subaddress());
            byte[] paymentId = hex.parseHex(vector.path("paymentIdHex").asText());
            assertArrayEquals(paymentId, decoded.paymentId());
            String encoded = paymentId.length == 0 ? MoneroPublicAddressCodec.encode(keys, network, decoded.subaddress())
                    : MoneroPublicAddressCodec.integrated(keys, network, paymentId);
            assertEquals(address, encoded); checked++;
        }
        assertEquals(6, checked);
    }
    @Test void alteredChecksumAndNoncanonicalBase58BlocksCannotBecomeRecipients() throws Exception {
        for (var vector : vectors()) {
            String address = vector.path("address").asText();
            String altered = address.substring(0,address.length()-1) + (address.endsWith("1") ? "2" : "1");
            assertThrows(IllegalArgumentException.class, () -> MoneroPublicAddressCodec.decode(altered));
            assertFalse(MoneroPublicAddressCodec.isValid(address.toLowerCase(),"regtest"));
        }
        assertThrows(IllegalArgumentException.class, () -> MoneroPublicAddressCodec.decode("z".repeat(95)));
        assertThrows(IllegalArgumentException.class, () -> MoneroPublicAddressCodec.decode("0".repeat(95)));
        assertThrows(IllegalArgumentException.class, () -> MoneroPublicAddressCodec.decode("1".repeat(94)));
    }
    @Test void networkIsBoundToTheAddressRatherThanTheCallerLabel() throws Exception {
        for (var vector : vectors()) {
            String address = vector.path("address").asText();
            assertTrue(MoneroPublicAddressCodec.isValid(address,"mainnet"));
            assertTrue(MoneroPublicAddressCodec.isValid(address,"regtest"));
            assertFalse(MoneroPublicAddressCodec.isValid(address,"testnet"));
            assertFalse(MoneroPublicAddressCodec.isValid(address,"stagenet"));
        }
        assertFalse(MoneroPublicAddressCodec.isValid(null,"mainnet"));
        assertFalse(MoneroPublicAddressCodec.isValid(vectors().get(0).path("address").asText(),"unknown"));
    }
    @Test void malformedOrSmallOrderPublicPointsAndPaymentIdsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> MoneroPublicAddressCodec.encode(new byte[64],"mainnet",true));
        assertThrows(IllegalArgumentException.class, () -> MoneroPublicAddressCodec.encode(new byte[63],"mainnet",true));
        var keys = hex.parseHex(vectors().get(0).path("publicKeyHex").asText());
        assertThrows(IllegalArgumentException.class, () -> MoneroPublicAddressCodec.integrated(keys,"mainnet",new byte[7]));
        assertThrows(IllegalArgumentException.class, () -> MoneroPublicAddressCodec.encode(keys,"unknown",true));
    }
}
