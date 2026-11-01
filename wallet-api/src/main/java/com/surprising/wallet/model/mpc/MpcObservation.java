package com.surprising.wallet.model.mpc;

/** Non-secret audit snapshot; usable signatures are stored separately as ciphertext. */
public record MpcObservation(String state, String intentHash) { }
