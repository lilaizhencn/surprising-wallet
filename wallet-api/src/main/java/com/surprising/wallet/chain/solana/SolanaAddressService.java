package com.surprising.wallet.chain.solana;

import org.springframework.stereotype.Service;

/** Public-only SPL ATA derivation. CustodyAddressService owns all joint-key address publication. */
@Service
public class SolanaAddressService {
    public String associatedTokenAddress(String ownerAddress,String mintAddress) {
        return MpcSolanaTransactionCodec.associatedTokenAddress(ownerAddress,mintAddress);
    }
}
