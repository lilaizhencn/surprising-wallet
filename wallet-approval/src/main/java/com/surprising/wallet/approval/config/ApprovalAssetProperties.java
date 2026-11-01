package com.surprising.wallet.approval.config;

import com.surprising.wallet.common.approval.ApprovalAssetSnapshot;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Independently installed public profiles. Wallet service clients cannot edit these values. */
@ConfigurationProperties("approval.assets")
public record ApprovalAssetProperties(List<ApprovalAssetSnapshot> entries) {
    public ApprovalAssetProperties { entries=entries==null?List.of():List.copyOf(entries); }
    @Configuration @EnableConfigurationProperties(ApprovalAssetProperties.class) public static class Binding { }
}
