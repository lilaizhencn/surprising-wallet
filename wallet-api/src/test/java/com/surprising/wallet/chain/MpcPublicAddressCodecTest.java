package com.surprising.wallet.chain;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashAddressCodec;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashNetworkParameters;
import com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import org.bitcoinj.base.Bech32;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.SegwitAddress;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.ton.ton4j.smartcontract.wallet.v4.WalletV4R2;

import static org.junit.jupiter.api.Assertions.*;

class MpcPublicAddressCodecTest {
    @Test void everyCurrentCbMpcChainHasAStablePublicOnlyAddress() throws Exception {
        byte[] secp = new ECKey().getPubKey();
        byte[] encoded = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded();
        byte[] ed = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        var evmAddresses = new HashSet<String>();
        int count = 0;
        for (var chain : ChainType.values()) {
            if (chain == ChainType.XMR) continue;
            var profile = profile(chain, "testnet");
            byte[] key = switch (chain) { case SOLANA, TON, APTOS, SUI, ADA, DOT, NEAR -> ed; default -> secp; };
            byte[] before = key.clone();
            String first = MpcPublicAddressCodec.encode(profile, key);
            assertFalse(first.isBlank(), chain.name());
            assertEquals(first, MpcPublicAddressCodec.encode(profile, key), chain.name());
            if (chain.isEvm()) evmAddresses.add(first);
            assertArrayEquals(before, key);
            count++;
        }
        assertEquals(27, count, "Adding a chain requires extending the MPC acceptance matrix");
        assertEquals(1, evmAddresses.size());
        assertThrows(IllegalArgumentException.class, () -> MpcPublicAddressCodec.encode(profile(ChainType.XMR, "testnet"), ed));
        assertThrows(IllegalArgumentException.class, () -> MpcPublicAddressCodec.encode(profile(ChainType.SOLANA, "testnet"), secp));
        assertThrows(IllegalArgumentException.class, () -> MpcPublicAddressCodec.encode(profile(ChainType.ETH, "testnet"), ed));
    }

    @Test void utxoOutputsCommitToJointKeyAndUseTheSelectedNetwork() {
        var key = ECKey.fromPublicOnly(new ECKey().getPubKey());
        for (var network : List.of("mainnet", "testnet", "regtest")) {
            for (var chain : List.of(ChainType.BTC, ChainType.LTC, ChainType.DOGE, ChainType.BCH)) {
                var params = BitcoinLikeNetwork.parse(network).parameters(chain.name());
                String address = MpcPublicAddressCodec.encode(profile(chain, network), key.getPubKey());
                byte[] outputHash = switch (chain) {
                    case BTC, LTC -> SegwitAddress.fromBech32(params, address).getWitnessProgram();
                    case DOGE -> LegacyAddress.fromBase58(params, address).getHash();
                    case BCH -> {
                        var decoded = BitcoinCashAddressCodec.decode(((BitcoinCashNetworkParameters) params).cashPrefix(), address);
                        assertEquals(0, decoded.type());
                        yield decoded.hash();
                    }
                    default -> throw new AssertionError();
                };
                assertArrayEquals(key.getPubKeyHash(), outputHash, chain + "/" + network);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> MpcPublicAddressCodec.encode(profile(ChainType.BTC, "unrecognized"), key.getPubKey()));
    }

    @Test void publicEdAddressesRetainChainSpecificEncodingAndTonNeverGeneratesAKeyPair() throws Exception {
        byte[] encoded = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded();
        byte[] key = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        assertEquals(HexFormat.of().formatHex(key), MpcPublicAddressCodec.encode(profile(ChainType.NEAR, "testnet"), key));
        assertArrayEquals(key, new org.p2p.solanaj.core.PublicKey(MpcPublicAddressCodec.encode(profile(ChainType.SOLANA, "testnet"), key)).toByteArray());
        byte[] aptos = Arrays.copyOf(key, 33);
        assertEquals("0x" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA3-256").digest(aptos)),
                MpcPublicAddressCodec.encode(profile(ChainType.APTOS, "testnet"), key));
        var wallet = WalletV4R2.builder().publicKey(key).walletId(698_983_191L ^ 0x8000_0000L).wc(0).build();
        assertNull(wallet.getKeyPair());
        assertArrayEquals(key, org.ton.ton4j.cell.CellSlice.beginParse(wallet.createDataCell()).skipBits(64).loadBytes(256));
        assertEquals(wallet.getAddress().toString(true, true, false, true), MpcPublicAddressCodec.encode(profile(ChainType.TON, "testnet"), key));
        for (var network : List.of("mainnet", "testnet")) {
            String ada = MpcPublicAddressCodec.encode(profile(ChainType.ADA, network), key);
            assertTrue(new ChainAddressRuntime().checkAddress(ChainType.ADA,ada));
            byte[] payload = Bech32.decodeBytes(ada, network.equals("mainnet") ? "addr" : "addr_test", Bech32.Encoding.BECH32);
            assertEquals(network.equals("mainnet") ? 0x61 : 0x60, payload[0] & 0xff);
            String dot = MpcPublicAddressCodec.encode(profile(ChainType.DOT, network), key);
            assertTrue(new ChainAddressRuntime().checkAddress(ChainType.DOT,dot));
            byte[] decoded = org.bitcoinj.base.Base58.decode(dot);
            assertArrayEquals(key, Arrays.copyOfRange(decoded, 1, 33));
            assertEquals(network.equals("mainnet") ? 0 : 42, decoded[0] & 0xff);
        }
    }

    private static AccountChainProfile profile(ChainType chain, String network) {
        return AccountChainProfile.builder().chain(chain.name()).network(network).build();
    }
}
