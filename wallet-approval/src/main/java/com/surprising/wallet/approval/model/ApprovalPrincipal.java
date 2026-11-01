package com.surprising.wallet.approval.model;

import java.util.UUID;

/** Resolved from an independent approval session, never from Wallet principal JSON. */
public record ApprovalPrincipal(UUID tenantId, UUID memberId, String role, String email, String displayName, String csrfToken) { }
