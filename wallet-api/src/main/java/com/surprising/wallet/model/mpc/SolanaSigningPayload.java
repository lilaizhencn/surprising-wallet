package com.surprising.wallet.model.mpc;

import com.surprising.wallet.chain.solana.MpcSolanaTransactionCodec;
import java.math.BigDecimal;

/** Immutable payment domain, original expiry and canonical raw message for every required signer. */
public record SolanaSigningPayload(String symbol,BigDecimal amount,BigDecimal customerFee,int decimals,
                                   String mint,String sender,String recipient,String feePayer,long lastValidBlockHeight,
                                   MpcSolanaTransactionCodec.Unsigned unsigned) { }
