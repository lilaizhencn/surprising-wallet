package com.surprising.wallet.model.custody;

/** Immutable operation identity read under its owning row lock. */
public record GasOperationReference(String chain, String referenceNo) { }
