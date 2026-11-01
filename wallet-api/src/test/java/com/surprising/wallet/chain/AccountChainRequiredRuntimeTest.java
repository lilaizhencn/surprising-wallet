package com.surprising.wallet.chain;

import com.surprising.wallet.chain.aptos.*;
import com.surprising.wallet.chain.cardano.*;
import com.surprising.wallet.chain.near.*;
import com.surprising.wallet.chain.polkadot.*;
import com.surprising.wallet.chain.solana.*;
import com.surprising.wallet.service.SolanaSubmissionService;
import com.surprising.wallet.service.AptosSubmissionService;
import com.surprising.wallet.service.TonSubmissionService;
import com.surprising.wallet.service.SuiSubmissionService;
import com.surprising.wallet.service.NearSubmissionService;
import com.surprising.wallet.service.CustodyGasService;
import com.surprising.wallet.chain.sui.*;
import com.surprising.wallet.chain.ton.*;
import com.surprising.wallet.chain.xrp.*;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.WalletSystemConfigRepository;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import com.surprising.wallet.service.WithdrawalSettlementService;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.DepositService;
import com.surprising.wallet.service.WithdrawalClaimService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

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
                var scanner = new AptosDepositScanner(
                        dependency(AptosRpcClient.class),
                        dependency(ChainAddressRepository.class),
                        dependency(ChainProfileRepository.class),
                        dependency(TokenConfigRepository.class),
                        dependency(ChainScanHeightRepository.class),
                        dependency(AptosTransactionRepository.class),
                        dependency(DepositService.class),
                        dependency(ChainAssetRepository.class),
                        runtime);
                var service = new AptosTransactionService(dependency(AptosRpcClient.class),dependency(AptosSubmissionService.class),
                        dependency(ChainProfileRepository.class),dependency(TokenConfigRepository.class),dependency(WithdrawalOrderRepository.class),
                        dependency(CollectionRecordRepository.class),runtime);
                yield new Actions(scanner::scanAndCredit, () -> service.collect(TENANT, "COLL", null));
            }
            case "ADA" -> {
                var scanner = new CardanoDepositScanner(dependency(CardanoRpcClient.class), dependency(ChainAddressRepository.class),
                        dependency(ChainProfileRepository.class), dependency(TokenConfigRepository.class), dependency(ChainAssetRepository.class),
                        dependency(ChainScanHeightRepository.class), dependency(ChainScanBlockRepository.class), dependency(CardanoOwnedOutputRepository.class),
                        mock(com.surprising.wallet.service.CardanoSubmissionService.class), dependency(DepositService.class),
                        dependency(org.springframework.transaction.PlatformTransactionManager.class), dependency(tools.jackson.databind.ObjectMapper.class), runtime);
                // Recovery is queried before new-work gates; no intent exists in this entry-gating scenario.
                var service = new CardanoTransactionService(dependency(CardanoRpcClient.class), mock(com.surprising.wallet.service.CardanoSubmissionService.class),
                        dependency(ChainProfileRepository.class), dependency(TokenConfigRepository.class), dependency(ChainAddressRepository.class),
                        dependency(CollectionRecordRepository.class), dependency(CustodyGasService.class), runtime, dependency(tools.jackson.databind.ObjectMapper.class));
                yield new Actions(scanner::scanAndCredit, () -> service.collect(TENANT, "COLL", null));
            }
            case "NEAR" -> {
                var scanner = new NearDepositScanner(
                        dependency(NearRpcClient.class),dependency(ChainScanHeightRepository.class),
                        dependency(NearTransactionRepository.class),dependency(DepositService.class),
                        dependency(ChainAddressRepository.class),dependency(ChainProfileRepository.class),
                        dependency(TokenConfigRepository.class),dependency(DepositRecordRepository.class),runtime);
                var service = new NearTransactionService(
                        dependency(NearRpcClient.class),dependency(NearSubmissionService.class),
                        dependency(ChainProfileRepository.class),dependency(TokenConfigRepository.class),
                        dependency(WithdrawalOrderRepository.class),dependency(CollectionRecordRepository.class),dependency(com.surprising.wallet.service.NearRegistrationService.class),runtime);
                yield new Actions(scanner::scanAndCredit, () -> service.collect(TENANT, "COLL", null));
            }
            case "DOT" -> {
                var scanner = new PolkadotDepositScanner(dependency(PolkadotRuntimeClient.class),dependency(ChainScanHeightRepository.class),dependency(DepositService.class),dependency(ChainAddressRepository.class),dependency(ChainProfileRepository.class),dependency(TokenConfigRepository.class),dependency(ChainAssetRepository.class),dependency(DepositRecordRepository.class),runtime);
                var service = new PolkadotTransactionService(dependency(PolkadotRuntimeClient.class),dependency(com.surprising.wallet.service.PolkadotSubmissionService.class),dependency(ChainProfileRepository.class),dependency(ChainAssetRepository.class),dependency(TokenConfigRepository.class),dependency(ChainAddressRepository.class),dependency(WithdrawalOrderRepository.class),dependency(CollectionRecordRepository.class),dependency(com.surprising.wallet.service.CustodyGasService.class),runtime);
                yield new Actions(scanner::scanAndCredit, () -> service.collect(TENANT,"COLL",null));
            }
            case "SOLANA" -> {
                var scanner = new SolanaDepositScanner(
                        dependency(SolanaRpcClient.class),
                        dependency(ChainAddressRepository.class),
                        dependency(ChainProfileRepository.class),
                        dependency(TokenConfigRepository.class),
                        dependency(ChainScanHeightRepository.class),
                        dependency(SolanaTransactionRepository.class),
                        dependency(DepositService.class),
                        dependency(DepositRecordRepository.class),
                        runtime,
                        dependency(SolanaAddressService.class));
                var service = new SolanaTransactionService(
                        dependency(SolanaRpcClient.class),
                        dependency(SolanaSubmissionService.class),
                        dependency(WithdrawalOrderRepository.class),
                        dependency(CollectionRecordRepository.class),
                        dependency(ChainProfileRepository.class),
                        dependency(TokenConfigRepository.class),
                        dependency(CustodyGasService.class),
                        runtime);
                yield new Actions(scanner::scanAndCredit, () -> service.collect(TENANT, "COLL", null));
            }
            case "SUI" -> {
                var scanner = new SuiDepositScanner(
                        dependency(SuiRpcClient.class),
                        dependency(ChainProfileRepository.class),
                        dependency(TokenConfigRepository.class),
                        dependency(ChainScanHeightRepository.class),
                        dependency(ChainAddressRepository.class),
                        dependency(SuiTransactionRepository.class),
                        dependency(DepositService.class),
                        dependency(ChainAssetRepository.class),
                        dependency(DepositRecordRepository.class),
                        runtime);
                var service = new SuiTransactionService(dependency(SuiRpcClient.class),dependency(SuiSubmissionService.class),
                        dependency(ChainProfileRepository.class),dependency(TokenConfigRepository.class),dependency(WithdrawalOrderRepository.class),
                        dependency(CollectionRecordRepository.class),runtime);
                yield new Actions(scanner::scanAndCredit, () -> service.collect(TENANT, "COLL", null));
            }
            case "TON" -> {
                var scanner = new TonDepositScanner(
                        dependency(TonCenterClient.class),
                        dependency(TonAddressService.class),
                        dependency(ChainAddressRepository.class),
                        dependency(ChainProfileRepository.class),
                        dependency(TokenConfigRepository.class),
                        dependency(ChainScanHeightRepository.class),
                        dependency(TonTransactionRepository.class),
                        dependency(DepositService.class),
                        dependency(DepositRecordRepository.class),
                        runtime);
                var service = new TonTransactionService(
                        dependency(TonCenterClient.class), dependency(TonSubmissionService.class),
                        dependency(ChainProfileRepository.class), dependency(TokenConfigRepository.class),
                        dependency(WithdrawalOrderRepository.class), dependency(CollectionRecordRepository.class), runtime);
                yield new Actions(scanner::scanAndCredit, () -> service.collect(TENANT,"COLL",null));
            }
            case "XRP" -> {
                var scanner = new XrpDepositScanner(
                        dependency(XrpRpcClient.class),
                        dependency(ChainScanHeightRepository.class),
                        dependency(ChainAddressRepository.class),
                        dependency(DepositService.class),
                        dependency(XrpTransactionRepository.class),
                        dependency(ChainProfileRepository.class),
                        dependency(TokenConfigRepository.class),
                        runtime);
                var service = new XrpTransactionService(
                        dependency(XrpRpcClient.class),
                        dependency(WithdrawalOrderRepository.class),
                        dependency(CollectionRecordRepository.class),
                        dependency(ChainProfileRepository.class),
                        dependency(TokenConfigRepository.class),
                        dependency(ChainAddressRepository.class),
                        dependency(com.surprising.wallet.service.XrpSubmissionService.class),
                        dependency(com.surprising.wallet.service.CustodyGasService.class),
                        dependency(com.surprising.wallet.service.WalletTaskLeaseService.class),runtime);
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
