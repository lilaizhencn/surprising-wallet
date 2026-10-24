package com.surprising.wallet.custody;

import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.chain.model.TokenConfiguration;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.WalletConfigOverviewService;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import com.surprising.wallet.testutil.CustodyTestSupport;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WalletConfigOverviewServiceTest {
    @Test
    void testEnvironmentAllowsDevAndTestProfilesWithOneEnabledNetwork() {
        var data = new Fixture();
        var summary = data.service("test2").summary(CustodyTestSupport.platformAdmin());
        assertFalse(summary.production());
        assertEquals(1, summary.statistics().enabledChainCount());
        assertEquals(1, summary.statistics().enabledNetworkCount());
        assertTrue(summary.anomalies().stream().noneMatch(row -> row.code().equals("MULTIPLE_ENABLED_NETWORKS")));
        assertEquals("ACTIVE", summary.chains().getFirst().status());
        assertEquals("DISABLED", summary.chains().get(1).status());
    }

    @Test
    void productionReportsMultipleAndNonProductionNetworks() {
        var data = new Fixture();
        data.profiles.forEach(profile -> profile.setEnabled(true));
        var summary = data.service("prod").summary(CustodyTestSupport.platformAdmin());
        assertTrue(summary.production());
        assertTrue(summary.anomalies().stream().anyMatch(row -> row.code().equals("MULTIPLE_ENABLED_NETWORKS")));
        assertEquals(2, summary.anomalies().stream().filter(row -> row.code().equals("NON_PRODUCTION_NETWORK")).count());
        assertTrue(summary.chains().stream().allMatch(row -> row.status().equals("BLOCKED")));
    }

    @Test
    void updatesAllGlobalSwitchesAndWritesAudit() {
        var data = new Fixture();
        var actor = CustodyTestSupport.platformAdmin();
        var summary = data.service("test2").updateGlobalSwitches(actor,
                new WalletConfigOverviewService.UpdateGlobalSwitchesCommand(true, false, true, false, true), "127.0.0.1");
        assertFalse(summary.globalSwitches().scanEnabled());
        assertFalse(summary.globalSwitches().collectionEnabled());
        assertEquals(5, data.switchUpdates);
        verify(data.audits).insert(any(UUID.class), isNull(), eq("PLATFORM_USER"), eq(actor.actorId().toString()),
                eq("WALLET_GLOBAL_SWITCHES.UPDATE"), eq("WALLET_GLOBAL_SWITCHES"), eq("global"), eq("127.0.0.1"),
                eq("{\"walletEnabled\":true,\"scanEnabled\":false,\"withdrawEnabled\":true,\"collectionEnabled\":false,\"transferEnabled\":true}"));
    }

    @Test
    void typedMetadataPreservesNetworkCountsAndTokenAssetDiagnostics() {
        var data = new Fixture();
        data.tokens.add(token("USDT", "DEVNET", "0x11"));
        data.tokens.add(token("USDC", "testnet", "0x22"));
        data.tokens.add(token("DAI", "devnet", "0x33"));
        data.assets.add(asset("USDT", "0x11", true));
        data.assets.add(asset("USDC", "0x22", false));
        data.assets.add(asset("DAI", null, true));
        data.assets.add(asset("ORPHAN", "0x44", true));
        var summary = data.service("test2").summary(CustodyTestSupport.platformAdmin());
        assertEquals(2, summary.chains().getFirst().enabledTokenCount());
        assertEquals(3, summary.statistics().enabledTokenCount());
        assertEquals(Set.of("TOKEN_NETWORK_DISABLED", "TOKEN_ASSET_MISSING", "TOKEN_CONTRACT_MISMATCH", "ASSET_TOKEN_MISSING"),
                summary.anomalies().stream().map(WalletConfigOverviewService.AnomalyView::code)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private static TokenConfiguration token(String symbol, String network, String contract) {
        return new TokenConfiguration(1, "ETH", network, symbol, "ERC20", contract, null, null, 6,
                true, false, null, null, null, null, null, null, null);
    }

    private static ChainAsset asset(String symbol, String contract, boolean active) {
        return ChainAsset.builder().chain("ETH").symbol(symbol).contractAddress(contract).active(active).build();
    }

    private static final class Fixture {
        final List<AccountChainProfile> profiles = new ArrayList<>();
        final List<TokenConfiguration> tokens = new ArrayList<>();
        final List<ChainAsset> assets = new ArrayList<>();
        final List<ChainRpcNodeRepository.Overview> nodes = new ArrayList<>();
        final Map<String, Boolean> switches = new HashMap<>();
        final WalletSystemConfigRepository settings = mock(WalletSystemConfigRepository.class);
        final CustodyAuditRepository audits = mock(CustodyAuditRepository.class);
        int switchUpdates;

        Fixture() {
            for (String key : List.of("all", "scan", "withdraw", "collection", "transfer")) {
                switches.put("global." + key + ".enabled", true);
            }
            profiles.add(profile(1, "devnet", true));
            profiles.add(profile(2, "testnet", false));
            for (String network : List.of("devnet", "testnet")) {
                for (String environment : List.of("test2", "prod")) {
                    nodes.add(new ChainRpcNodeRepository.Overview("ETH", network, environment, true));
                }
            }
            when(settings.globalSwitchValues()).thenAnswer(ignored -> Map.copyOf(switches));
            doAnswer(call -> {
                switches.put(call.getArgument(0), call.getArgument(1));
                switchUpdates++;
                return null;
            }).when(settings).upsertBoolean(anyString(), anyBoolean());
        }

        WalletConfigOverviewService service(String environment) {
            var profileRows = mock(ChainProfileRepository.class);
            var tokenRows = mock(TokenConfigRepository.class);
            var assetRows = mock(ChainAssetRepository.class);
            var rpcRows = mock(ChainRpcNodeRepository.class);
            var keys = mock(WalletKeyMaterialProvider.class);
            when(profileRows.listAll()).thenAnswer(ignored -> List.copyOf(profiles));
            when(tokenRows.listAll()).thenAnswer(ignored -> List.copyOf(tokens));
            when(assetRows.listNonNativeOverview()).thenAnswer(ignored -> List.copyOf(assets));
            when(rpcRows.listOverview()).thenAnswer(ignored -> List.copyOf(nodes));
            when(keys.hasApiKeyMaterial()).thenReturn(true);
            return new WalletConfigOverviewService(settings, profileRows, tokenRows, assetRows, rpcRows, audits,
                    keys, environment, new WalletRuntimeConfigService(profileRows, settings));
        }

        private static AccountChainProfile profile(long id, String network, boolean enabled) {
            return AccountChainProfile.builder().id(id).chain("ETH").network(network).family("evm")
                    .enabled(enabled).scanEnabled(true).withdrawEnabled(true).collectionEnabled(true).transferEnabled(true).build();
        }
    }
}
