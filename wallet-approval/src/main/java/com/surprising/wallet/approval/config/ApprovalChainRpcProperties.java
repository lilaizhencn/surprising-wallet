package com.surprising.wallet.approval.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Installed on the independent authority host. No request can select an endpoint or supply network facts. */
@ConfigurationProperties("approval.chain-rpc")
public record ApprovalChainRpcProperties(List<Profile> profiles,Duration timeout) {
    public ApprovalChainRpcProperties {
        profiles=profiles==null?List.of():List.copyOf(profiles);timeout=timeout==null?Duration.ofSeconds(20):timeout;
        if(!timeout.isPositive() || timeout.compareTo(Duration.ofSeconds(60))>0
                || profiles.stream().map(p->p.chain()+":"+p.network()+":"+p.role()).distinct().count()!=profiles.size())
            throw new IllegalArgumentException("invalid independent account-chain RPC profiles or deadline");
    }
    public record Profile(String chain,String network,String role,String networkIdentity,URI endpoint,Path headersFile,Map<String,String> parameters) {
        public Profile {
            com.surprising.wallet.common.chain.ChainType.valueOf(chain);
            role=role==null?"DEFAULT":role;parameters=parameters==null?Map.of():Map.copyOf(parameters);
            if(network==null || !network.matches("[a-zA-Z0-9_.:-]{1,128}") || !role.matches("[A-Z_]{1,32}")
                    || networkIdentity==null || !networkIdentity.matches("[a-zA-Z0-9_.:-]{1,256}")
                    || endpoint==null || endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getRawQuery()!=null || endpoint.getFragment()!=null)
                throw new IllegalArgumentException("invalid independent account-chain RPC domain");
            boolean local=List.of("localhost","127.0.0.1","[::1]").contains(endpoint.getHost());
            if(!"https".equals(endpoint.getScheme()) && !(local && "http".equals(endpoint.getScheme())))
                throw new IllegalArgumentException("independent account-chain RPC requires HTTPS or loopback HTTP");
        }
    }
    @Configuration @EnableConfigurationProperties(ApprovalChainRpcProperties.class) public static class Binding { }
}
