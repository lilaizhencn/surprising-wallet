package com.surprising.wallet.chain.model;

import java.time.Instant;
import java.util.UUID;

/** A tenant-owned custody_address row, mapped once by its table repository. */
public record CustodyAddressRecord(UUID id, UUID tenantId, long chainAddressId, String chain, String network,
                                   String address, String memo, String subject, String label, String metadataJson,
                                   String source, String status, int derivationSubject, long addressVersion,
                                   long derivationChild, Instant createdAt, Instant updatedAt) { }
