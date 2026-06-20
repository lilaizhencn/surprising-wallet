package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvmNonceRecord implements Serializable {
    private Long id;
    private String chain;
    private String address;
    private Long chainNonce;
    private Long reservedNonce;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
