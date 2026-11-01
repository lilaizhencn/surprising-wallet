package com.surprising.wallet.approval.config;

import com.surprising.wallet.common.signing.MpcClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Credentials belong to the independent authority and should have READ_RESULT permission only. */
@ConfigurationProperties("approval.native-nodes")
public record ApprovalNativeProperties(Path caFile,Path certificateFile,Path privateKeyFile,List<MpcClient.Node> nodes,Duration callTimeout) {
    public ApprovalNativeProperties {
        nodes=nodes==null?List.of():List.copyOf(nodes);
        callTimeout=callTimeout==null?Duration.ofSeconds(3):callTimeout;
        if(!nodes.isEmpty() && (nodes.size()!=3 || nodes.stream().map(MpcClient.Node::partyId).distinct().count()!=3
                || nodes.stream().map(MpcClient.Node::endpoint).distinct().count()!=3 || caFile==null || certificateFile==null || privateKeyFile==null))
            throw new IllegalArgumentException("independent native registry requires three distinct holders and its own mTLS identity");
        if(nodes.isEmpty() && (caFile!=null || certificateFile!=null || privateKeyFile!=null)) throw new IllegalArgumentException("incomplete independent native registry profile");
        if(!callTimeout.isPositive() || callTimeout.compareTo(Duration.ofSeconds(30))>0) throw new IllegalArgumentException("native registry call timeout exceeds bounds");
    }
    @Configuration @EnableConfigurationProperties(ApprovalNativeProperties.class) public static class Binding { }
}
