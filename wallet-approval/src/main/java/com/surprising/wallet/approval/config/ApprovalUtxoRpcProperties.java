package com.surprising.wallet.approval.config;

import com.surprising.wallet.common.chain.ChainType;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Independently installed read-only node profiles. Neither endpoints nor credentials are accepted in service requests. */
@ConfigurationProperties("approval.utxo-rpc")
public record ApprovalUtxoRpcProperties(List<Profile> profiles,Duration timeout) {
    public ApprovalUtxoRpcProperties {
        profiles=profiles==null?List.of():List.copyOf(profiles);timeout=timeout==null?Duration.ofSeconds(10):timeout;
        if(!timeout.isPositive() || timeout.compareTo(Duration.ofSeconds(30))>0
                || profiles.stream().map(p->p.chain()+":"+p.network()).distinct().count()!=profiles.size())
            throw new IllegalArgumentException("invalid independent UTXO RPC profile or bounded timeout");
    }
    public record Profile(String chain,String network,String rpcChainName,String genesisHash,URI endpoint,Path basicAuthFile,int minimumConfirmations,
            int maximumRbfVersions,long maximumRbfFeeRate,long maximumRbfFeeAtomic) {
        public Profile {
            if(chain==null || !ChainType.valueOf(chain).isBitcoinLike() || network==null || !network.matches("[a-zA-Z0-9_.:-]{1,128}")
                    || rpcChainName==null || !rpcChainName.matches("[a-zA-Z0-9_-]{1,32}") || genesisHash==null || !genesisHash.matches("[0-9a-f]{64}")
                    || endpoint==null || endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getRawQuery()!=null || endpoint.getFragment()!=null
                    || minimumConfirmations<1 || minimumConfirmations>1_000_000)
                throw new IllegalArgumentException("invalid independent UTXO network profile");
            com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork.parse(network);
            if(maximumRbfVersions<0 || maximumRbfVersions>5 || (maximumRbfVersions>0 && (!chain.equals("BTC") || maximumRbfVersions<2
                    || maximumRbfFeeRate<1 || maximumRbfFeeRate>1000 || maximumRbfFeeAtomic<1 || maximumRbfFeeAtomic>100000))
                    || maximumRbfVersions==0 && (maximumRbfFeeRate!=0 || maximumRbfFeeAtomic!=0))
                throw new IllegalArgumentException("BTC RBF requires explicit bounded versions/rate/fee; zero disables replacements");
            boolean local=List.of("localhost","127.0.0.1","[::1]").contains(endpoint.getHost());
            if(!"https".equals(endpoint.getScheme()) && !(local && "http".equals(endpoint.getScheme())))
                throw new IllegalArgumentException("independent UTXO RPC requires HTTPS or local-loopback HTTP");
        }
    }
    @Configuration @EnableConfigurationProperties(ApprovalUtxoRpcProperties.class) public static class Binding { }
}
