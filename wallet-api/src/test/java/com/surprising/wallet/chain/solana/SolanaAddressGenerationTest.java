package com.surprising.wallet.chain.solana;

import org.junit.jupiter.api.Test;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.PublicKey;
import static org.junit.jupiter.api.Assertions.*;

/** ATA derivation consumes a public owner; joint-key address publication is covered by custody tests. */
class SolanaAddressGenerationTest {
    @Test void publicOwnerDerivesTheSameAtaAsTheIndependentSdk() throws Exception {
        var owner=new Account().getPublicKey();
        var mint=new PublicKey("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v");
        var service=new SolanaAddressService();
        var expected=PublicKey.findProgramAddress(java.util.List.of(owner.toByteArray(),
                org.p2p.solanaj.programs.TokenProgram.PROGRAM_ID.toByteArray(),mint.toByteArray()),
                org.p2p.solanaj.programs.AssociatedTokenProgram.PROGRAM_ID).getAddress().toBase58();
        assertEquals(expected,service.associatedTokenAddress(owner.toBase58(),mint.toBase58()));
        assertNotEquals(expected,service.associatedTokenAddress(new Account().getPublicKeyBase58(),mint.toBase58()));
    }
}
