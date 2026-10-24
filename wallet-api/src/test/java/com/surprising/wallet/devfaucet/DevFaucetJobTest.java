package com.surprising.wallet.devfaucet;

import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.chain.model.CustodyAddressRecord;
import com.surprising.wallet.chain.model.TokenConfiguration;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.job.devfaucet.DevFaucetJob;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.DevFaucetRpcClient;
import com.surprising.wallet.service.DevFaucetService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DevFaucetJobTest {
    @Test
    void springCreatesTheServiceAndJobWithTheirOnlyConstructors() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("dev-faucet-test",
                    Map.of("sw.wallet.dev-faucet.enabled", "true", "sw.app.env.name", "test")));
            context.registerBean(DevFaucetProperties.class, DevFaucetPropertiesTest::validProperties);
            for (Class<?> type : List.of(DevFaucetFundingRepository.class, CustodyAddressRepository.class,
                    CustodyTenantTableRepository.class, CustodyTenantChainTableRepository.class,
                    CustodyGasAccountRepository.class, ChainProfileRepository.class, ChainAssetRepository.class,
                    TokenConfigRepository.class, CustodyDepositRepository.class, CustodyAuditRepository.class,
                    DevFaucetRpcClient.class)) registerMock(context, type);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(DevFaucetService.class, DevFaucetJob.class, DevFaucetAmountGenerator.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(DevFaucetJob.class).size());
        }
    }

    private static <T> void registerMock(AnnotationConfigApplicationContext context, Class<T> type) {
        context.registerBean(type, () -> mock(type));
    }

    @Test
    void discoversNativeAndMatchingTokensForApiCustomersButOnlyNativeForGasAndDoesNotRepeatFunding() {
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID gas = UUID.randomUUID();
        UUID console = UUID.randomUUID();
        var fundings = mock(DevFaucetFundingRepository.class);
        var addresses = mock(CustodyAddressRepository.class);
        var tenants = mock(CustodyTenantTableRepository.class);
        var chains = mock(CustodyTenantChainTableRepository.class);
        var gasAccounts = mock(CustodyGasAccountRepository.class);
        var profiles = mock(ChainProfileRepository.class);
        var assets = mock(ChainAssetRepository.class);
        var tokens = mock(TokenConfigRepository.class);
        when(tenants.listActiveIds()).thenReturn(List.of(tenant));
        when(chains.listActiveByTenants(List.of(tenant))).thenReturn(List.of(
                new CustodyTenantChainTableRepository.TenantChain(tenant, "ETH")));
        when(gasAccounts.listActiveAddressIds()).thenReturn(List.of(gas));
        when(profiles.listProfiles(true)).thenReturn(List.of(AccountChainProfile.builder()
                .chain("ETH").network("devtest").scanEnabled(true).build()));
        when(addresses.listActiveForTenantsAndChains(List.of(tenant), List.of("BTC", "ETH")))
                .thenReturn(List.of(address(tenant, customer, "API"), address(tenant, gas, "CONSOLE"),
                        address(tenant, console, "CONSOLE")));
        when(assets.listActiveDefinitions()).thenReturn(List.of(
                asset("ETH", null, true, 18), asset("USDT", "0x11", false, 6),
                asset("USDC", "0x22", false, 6)));
        when(tokens.listAll()).thenReturn(List.of(token("USDT", "0x11", "devtest"),
                token("USDC", "0x22", "devtest"), token("USDT", "0x33", "other-network")));
        var created = new LinkedHashMap<DevFaucetFundingRepository.FundingKey, DevFaucetFunding>();
        when(fundings.existingForAddresses(anyList())).thenAnswer(ignored -> List.copyOf(created.keySet()));
        when(fundings.create(any(), any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), anyInt(), any())).thenAnswer(call -> {
            UUID address = call.getArgument(1);
            String symbol = call.getArgument(4);
            String purpose = call.getArgument(5);
            var funding = new DevFaucetFunding(UUID.randomUUID(), tenant, address, call.getArgument(2),
                    call.getArgument(3), symbol, purpose, call.getArgument(6), call.getArgument(7),
                    call.getArgument(8), call.getArgument(9), 0);
            return created.putIfAbsent(new DevFaucetFundingRepository.FundingKey(address, symbol, purpose), funding) == null;
        });
        Set<UUID> sent = new HashSet<>();
        when(fundings.due(anyInt(), anyInt())).thenAnswer(ignored -> created.values().stream()
                .filter(row -> !sent.contains(row.id())).toList());
        when(fundings.markSending(any())).thenReturn(true);
        doAnswer(call -> { sent.add(call.getArgument(0)); return null; }).when(fundings).markSent(any(), any());
        var rpc = mock(DevFaucetRpcClient.class);
        when(rpc.send(any())).thenReturn("test-transaction");
        var service = new DevFaucetService(DevFaucetPropertiesTest.validProperties(), fundings, addresses,
                tenants, chains, gasAccounts, profiles, assets, tokens, mock(CustodyDepositRepository.class),
                rpc, mock(CustodyAuditRepository.class), new ObjectMapper(), new DevFaucetAmountGenerator(), "test");
        service.validate();
        service.runOnce();
        service.runOnce();
        assertEquals(4, sent.size());
        assertEquals(Set.of("ETH", "USDT", "USDC"), created.values().stream()
                .filter(row -> row.custodyAddressId().equals(customer)).map(DevFaucetFunding::assetSymbol)
                .collect(java.util.stream.Collectors.toSet()));
        var gasFunding = created.values().stream().filter(row -> row.custodyAddressId().equals(gas)).toList();
        assertEquals(1, gasFunding.size());
        assertEquals("TENANT_GAS", gasFunding.getFirst().purpose());
        assertEquals(new BigDecimal("1.000000"), gasFunding.getFirst().requestedAmount());
        assertFalse(created.values().stream().anyMatch(row -> row.custodyAddressId().equals(console)));
        verify(rpc, times(4)).send(any());
    }

    private static CustodyAddressRecord address(UUID tenant, UUID id, String source) {
        return new CustodyAddressRecord(id, tenant, 1, "ETH", "devtest", "0xab", null, "customer", null,
                null, source, "ACTIVE", 1, 1, 0, null, null);
    }

    private static ChainAsset asset(String symbol, String contract, boolean nativeAsset, int decimals) {
        return ChainAsset.builder().chain("ETH").symbol(symbol).contractAddress(contract)
                .nativeAsset(nativeAsset).decimals(decimals).active(true).build();
    }

    private static TokenConfiguration token(String symbol, String contract, String network) {
        return new TokenConfiguration(1, "ETH", network, symbol, "ERC20", contract, null, null, 6,
                true, true, null, null, null, null, null, null, null);
    }
}
