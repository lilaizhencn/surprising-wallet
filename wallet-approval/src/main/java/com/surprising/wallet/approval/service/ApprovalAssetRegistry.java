package com.surprising.wallet.approval.service;

import com.surprising.wallet.approval.config.ApprovalAssetProperties;
import com.surprising.wallet.common.approval.ApprovalAssetSnapshot;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ApprovalAssetRegistry {
    private final Map<Identity,ApprovalAssetSnapshot> assets;
    public ApprovalAssetRegistry(ApprovalAssetProperties properties) {
        var registered=new HashMap<Identity,ApprovalAssetSnapshot>();
        var networks=new HashMap<Network,NativeIdentity>();
        for(var asset:properties.entries()) {
            if(registered.putIfAbsent(new Identity(asset.chain(),asset.network(),asset.symbol()),asset)!=null)
                throw new IllegalArgumentException("duplicate independently configured asset domain");
            var nativeIdentity=new NativeIdentity(asset.networkIdentity(),asset.nativeSymbol(),asset.nativeDecimals());
            var old=networks.putIfAbsent(new Network(asset.chain(),asset.network()),nativeIdentity);
            if(old!=null && !old.equals(nativeIdentity)) throw new IllegalArgumentException("assets disagree on independent native network identity or precision");
        }
        assets=Map.copyOf(registered);
    }
    public ApprovalAssetSnapshot require(String chain,String network,String symbol) {
        var asset=assets.get(new Identity(chain,network,symbol));
        if(asset==null) throw new IllegalStateException("asset/network is not independently registered");
        return asset;
    }
    public void requireCurrent(ApprovalAssetSnapshot snapshot) {
        if(!require(snapshot.chain(),snapshot.network(),snapshot.symbol()).equals(snapshot))
            throw new IllegalStateException("independent asset profile changed after review");
    }
    private record Identity(String chain,String network,String symbol) { }
    private record Network(String chain,String network) { }
    private record NativeIdentity(String networkIdentity,String symbol,int decimals) { }
}
