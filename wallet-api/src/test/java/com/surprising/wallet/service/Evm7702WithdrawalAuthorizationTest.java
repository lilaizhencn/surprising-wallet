package com.surprising.wallet.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Evm7702WithdrawalAuthorizationTest {
    private static final String COLLECTION_DELEGATE = "0x5FC8d32690cc91D4c39d9d3abcBD16989F875707";
    private static final String PAYOUT_DELEGATE = "0x0165878A594ca255338adfa4d48449f69242Eb8F";

    @Test
    void emptyAccountCodeAuthorizesConfiguredPayoutDelegate() {
        assertEquals(Evm7702WithdrawalWorkflowService.PayoutAuthorizationMode.AUTHORIZE_PAYOUT,
                mode("0x"));
    }

    @Test
    void configuredCollectionDelegateMayBeReplacedByConfiguredPayoutDelegate() {
        assertEquals(Evm7702WithdrawalWorkflowService.PayoutAuthorizationMode.AUTHORIZE_PAYOUT,
                mode("0xef0100" + COLLECTION_DELEGATE.substring(2).toLowerCase()));
    }

    @Test
    void existingPayoutDelegateNeedsNoNewAuthorization() {
        assertEquals(Evm7702WithdrawalWorkflowService.PayoutAuthorizationMode.ALREADY_PAYOUT_DELEGATED,
                mode("0xef0100" + PAYOUT_DELEGATE.substring(2).toLowerCase()));
    }

    @Test
    void unknownDelegationIsRejected() {
        assertEquals(Evm7702WithdrawalWorkflowService.PayoutAuthorizationMode.REJECT,
                mode("0xef010000000000000000000000000000000000000001"));
    }

    private static Evm7702WithdrawalWorkflowService.PayoutAuthorizationMode mode(String code) {
        return Evm7702WithdrawalWorkflowService.payoutAuthorizationMode(
                code, COLLECTION_DELEGATE, PAYOUT_DELEGATE);
    }
}
