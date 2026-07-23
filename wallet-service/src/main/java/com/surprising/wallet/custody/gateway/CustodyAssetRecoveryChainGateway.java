package com.surprising.wallet.custody.gateway;

import com.surprising.wallet.common.chain.ChainAddressRecord;

import java.math.BigDecimal;

public interface CustodyAssetRecoveryChainGateway {
    boolean supports(String chain);

    Verification verify(VerificationRequest request);

    String execute(ExecutionRequest request);

    boolean confirmed(String chain, String txHash);

    final class PermanentlyFailedTransactionException extends RuntimeException {
        public PermanentlyFailedTransactionException(String message) {
            super(message);
        }
    }

    record VerificationRequest(
            String chain, String assetSymbol, String tokenContract, String txHash,
            Long requestedLogIndex, String destinationAddress, BigDecimal claimedAmount) {
    }

    record Verification(
            String tokenContract, Integer tokenDecimals, long logIndex, BigDecimal amount,
            long blockHeight, String blockHash, int confirmations, String detailsJson) {
    }

    record ExecutionRequest(
            String chain, String assetSymbol, String tokenContract, int tokenDecimals,
            BigDecimal amount, ChainAddressRecord source, String recoveryAddress) {
    }
}
