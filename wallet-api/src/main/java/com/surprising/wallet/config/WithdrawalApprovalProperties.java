package com.surprising.wallet.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Public authority pin and Wallet-only mTLS credentials. Never an issuer private key. */
@ConfigurationProperties("sw.approval")
public record WithdrawalApprovalProperties(URI origin,String serverSpki,String verificationKeyHex,
        Path caFile,Path certificateFile,Path privateKeyFile,Duration timeout) {
    public WithdrawalApprovalProperties {
        timeout=timeout==null?Duration.ofSeconds(5):timeout;
        if(!timeout.isPositive() || timeout.compareTo(Duration.ofSeconds(30))>0) throw new IllegalArgumentException("invalid approval timeout");
        boolean any=origin!=null || serverSpki!=null || verificationKeyHex!=null || caFile!=null || certificateFile!=null || privateKeyFile!=null;
        if(any && (origin==null || serverSpki==null || verificationKeyHex==null || caFile==null || certificateFile==null || privateKeyFile==null
                || !"https".equals(origin.getScheme()) || origin.getHost()==null || origin.getUserInfo()!=null || origin.getQuery()!=null || origin.getFragment()!=null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/")) || !serverSpki.matches("[0-9a-f]{64}") || !verificationKeyHex.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("incomplete independent approval client profile");
    }
    @Configuration @EnableConfigurationProperties(WithdrawalApprovalProperties.class) public static class Binding { }
}
