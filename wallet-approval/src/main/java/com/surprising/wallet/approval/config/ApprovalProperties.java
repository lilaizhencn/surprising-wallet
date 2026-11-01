package com.surprising.wallet.approval.config;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties("approval")
public record ApprovalProperties(String publicOrigin,Map<String,Set<UUID>> serviceClients) {
    public ApprovalProperties {
        var origin=URI.create(publicOrigin==null?"":publicOrigin);
        if(!"https".equals(origin.getScheme()) || origin.getHost()==null || origin.getUserInfo()!=null
                || origin.getQuery()!=null || origin.getFragment()!=null || !origin.getPath().isEmpty())
            throw new IllegalArgumentException("approval.public-origin must be an HTTPS origin without a path");
        serviceClients=serviceClients==null?Map.of():Map.copyOf(serviceClients);
        serviceClients.forEach((identity,tenants)->{
            if(!identity.matches("[0-9a-f]{64}") || tenants.isEmpty()) throw new IllegalArgumentException("approval service mTLS identity/tenants invalid");
        });
    }
    @Configuration @EnableConfigurationProperties(ApprovalProperties.class) public static class Binding { }
}
