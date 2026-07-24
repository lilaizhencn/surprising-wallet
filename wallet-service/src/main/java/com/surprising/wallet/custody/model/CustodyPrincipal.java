package com.surprising.wallet.custody.model;

import java.util.Set;
import java.util.UUID;

public record CustodyPrincipal(
        ActorType actorType,
        UUID actorId,
        UUID tenantId,
        String tenantSlug,
        String role,
        Set<String> scopes
) {    public enum ActorType {
        PLATFORM_USER,
        TENANT_USER,
        API_KEY
    }
    public boolean isPlatformAdmin() {
        return actorType == ActorType.PLATFORM_USER && "PLATFORM_ADMIN".equals(role);
    }
    public boolean hasScope(String scope) {
        return scopes != null && (scopes.contains("*") || scopes.contains(scope));
    }
}
