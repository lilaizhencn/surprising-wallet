package com.surprising.wallet.approval.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The issuer private key belongs only to the independently deployed authority. */
@ConfigurationProperties("approval.signing")
public record ApprovalSigningProperties(Path privateKeyFile,String publicKeyHex) {
    public ApprovalSigningProperties {
        if((privateKeyFile==null)!=(publicKeyHex==null)) throw new IllegalArgumentException("incomplete independent authorization signing profile");
        if(publicKeyHex!=null && !publicKeyHex.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid independent authorization verification key");
    }
    @Configuration @EnableConfigurationProperties(ApprovalSigningProperties.class) public static class Binding { }
}
