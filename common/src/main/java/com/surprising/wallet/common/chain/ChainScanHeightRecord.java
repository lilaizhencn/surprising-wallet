package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Runtime scanner checkpoint from chain_scan_height.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainScanHeightRecord {
    private String chain;
    private String scannerName;
    private Long bestHeight;
    private Long safeHeight;
    private String status;
    private Instant updatedAt;
}
