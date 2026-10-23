package com.surprising.wallet.chain;

import com.surprising.wallet.chain.aptos.*;
import com.surprising.wallet.chain.cardano.*;
import com.surprising.wallet.chain.near.*;
import com.surprising.wallet.chain.polkadot.*;
import com.surprising.wallet.chain.solana.*;
import com.surprising.wallet.chain.sui.*;
import com.surprising.wallet.chain.ton.*;
import com.surprising.wallet.chain.xrp.*;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.repository.ChainJdbcRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.WalletSystemConfigRepository;
import com.surprising.wallet.service.HotWalletAddressService;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import com.surprising.wallet.service.WithdrawalSettlementService;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountChainRequiredRuntimeTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final List<Object> dependencies = new ArrayList<>();

    @ParameterizedTest
    @ValueSource(strings = {"APTOS", "ADA", "NEAR", "DOT", "SOLANA", "SUI", "TON", "XRP"})
    void globalAndChainSwitchesStopScanningAndCollectionBeforeBusinessOrRpcAccess(String chain) {
        var profiles = mock(ChainProfileRepository.class);
        var settings = mock(WalletSystemConfigRepository.class);
        var runtime = new WalletRuntimeConfigService(profiles, settings);
        var actions = actions(chain, runtime);
        when(settings.find("global.all.enabled")).thenReturn(Optional.of(new WalletSystemConfigRepository.Setting("false", true)));
        assertDisabled(chain, actions);
        verifyNoInteractions(dependencies.toArray());

        when(settings.find("global.all.enabled")).thenReturn(Optional.of(new WalletSystemConfigRepository.Setting("true", true)));
        when(profiles.findAccountByChain(chain)).thenReturn(Optional.of(AccountChainProfile.builder()
                .chain(chain).enabled(true).scanEnabled(false).collectionEnabled(false).build()));
        assertDisabled(chain, actions);
        verifyNoInteractions(dependencies.toArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {"APTOS", "ADA", "NEAR", "DOT", "SOLANA", "SUI", "TON", "XRP"})
    void missingRuntimeCannotConstructAnExecutableScanner(String chain) {
        assertThrows(NullPointerException.class, () -> actions(chain, null));
    }

    private void assertDisabled(String chain, Actions actions) {
        for (Executable operation : List.of(actions.scan(), actions.collect())) {
            var error = assertThrows(IllegalStateException.class, operation);
            assertTrue(error.getMessage().contains("wallet runtime switch disabled: chain=" + chain));
        }
    }

    private Actions actions(String chain, WalletRuntimeConfigService runtime) {
        return switch (chain) {
            case "APTOS" -> {
                var scanner = new AptosDepositScanner(dependency(AptosRpcClient.class), dependency(ChainJdbcRepository.class), runtime);
                var service = new AptosTransactionService(dependency(AptosRpcClient.class), dependency(AptosTransactionSigner.class),
                        dependency(ChainJdbcRepository.class), dependency(WithdrawalSettlementService.class), runtime);
                yield new Actions(scanner::scanAndCredit, () -> service.collectNative(TENANT, "COLL", null, "target", BigDecimal.ONE));
            }
            case "ADA" -> {
                var scanner = new CardanoDepositScanner(dependency(CardanoBackendClient.class), dependency(ChainJdbcRepository.class), runtime);
                var service = new CardanoTransactionService(dependency(CardanoBackendClient.class), dependency(CardanoKeyService.class),
                        dependency(ChainJdbcRepository.class), dependency(WithdrawalSettlementService.class), runtime);
                yield new Actions(scanner::scanAndCredit, () -> service.collectNative(TENANT, "COLL", null, "target", BigInteger.ONE));
            }
            case "NEAR" -> {
                var scanner = new NearDepositScanner(dependency(NearRpcClient.class), dependency(ChainJdbcRepository.class), runtime);
                var service = new NearTransactionService(dependency(NearRpcClient.class), dependency(NearTransactionSigner.class),
                        dependency(NearKeyService.class), dependency(ChainJdbcRepository.class), dependency(WithdrawalSettlementService.class), runtime);
                yield new Actions(scanner::scanAndCredit, () -> service.collectNative(TENANT, "COLL", null, "target", BigInteger.ONE));
            }
            case "DOT" -> {
                var scanner = new PolkadotDepositScanner(dependency(PolkadotRuntimeClient.class), dependency(ChainJdbcRepository.class), runtime);
                var service = new PolkadotTransactionService(dependency(PolkadotRuntimeClient.class), dependency(PolkadotKeyService.class),
                        dependency(ChainJdbcRepository.class), dependency(HotWalletAddressService.class), dependency(WithdrawalSettlementService.class), runtime);
                yield new Actions(scanner::scanAndCredit, () -> service.collectNative(TENANT, "COLL", null, "target", BigInteger.ONE));
            }
            case "SOLANA" -> {
                var scanner = new SolanaDepositScanner(dependency(SolanaRpcClient.class), dependency(ChainJdbcRepository.class), runtime,
                        dependency(SolanaAddressService.class));
                var service = new SolanaTransactionService(dependency(SolanaRpcClient.class), dependency(SolanaKeyService.class),
                        dependency(SolanaAddressService.class), dependency(ChainJdbcRepository.class), dependency(WithdrawalSettlementService.class), runtime);
                yield new Actions(scanner::scanAndCredit, () -> service.collectNative(TENANT, "COLL", null, "target", BigDecimal.ONE));
            }
            case "SUI" -> {
                var scanner = new SuiDepositScanner(dependency(SuiRpcClient.class), dependency(ChainJdbcRepository.class), runtime);
                var service = new SuiTransactionService(dependency(SuiRpcClient.class), dependency(SuiTransactionSigner.class),
                        dependency(SuiPtbTransactionBuilder.class), dependency(ChainJdbcRepository.class), dependency(WithdrawalSettlementService.class), runtime);
                yield new Actions(scanner::scanAndCredit, () -> service.collectNative(TENANT, "COLL", null, "target", BigDecimal.ONE));
            }
            case "TON" -> {
                var scanner = new TonDepositScanner(dependency(TonCenterClient.class), dependency(TonAddressService.class),
                        dependency(ChainJdbcRepository.class), dependency(TonApiClient.class), runtime);
                var service = new TonTransactionService(dependency(TonCenterClient.class), dependency(TonKeyService.class),
                        dependency(ChainJdbcRepository.class), dependency(WithdrawalSettlementService.class), runtime);
                yield new Actions(scanner::scanAndCredit, () -> service.collectNative(TENANT, "COLL", null, "target", BigDecimal.ONE, "memo"));
            }
            case "XRP" -> {
                var scanner = new XrpDepositScanner(dependency(XrpRpcClient.class), dependency(ChainJdbcRepository.class), runtime);
                var service = new XrpTransactionService(dependency(XrpRpcClient.class), dependency(XrpKeyService.class),
                        dependency(ChainJdbcRepository.class), dependency(WithdrawalSettlementService.class), runtime);
                yield new Actions(scanner::scanAndCredit, () -> service.collectNative(TENANT, "COLL", null, "target", BigDecimal.ONE));
            }
            default -> throw new IllegalArgumentException(chain);
        };
    }

    private <T> T dependency(Class<T> type) {
        T dependency = mock(type);
        dependencies.add(dependency);
        return dependency;
    }

    private record Actions(Executable scan, Executable collect) { }
}
