package com.surprising.wallet.jobs.account;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.service.chain.evm.EvmAccountTransactionService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountChainWorkflowServiceTest {

    @Test
    void broadcastFailureKeepsFundsLockedForManualAudit() throws Exception {
        ChainAddressRecord address = ChainAddressRecord.builder()
                .chain("ETH")
                .assetSymbol("ETH")
                .accountId("0xsource")
                .userId(1L)
                .biz(0)
                .addressIndex(1L)
                .address("0xsource")
                .ownerAddress("0xsource")
                .enabled(true)
                .build();
        FakeRepository repository = new FakeRepository(address);
        AccountChainWorkflowService service = service(repository, new FailingEvmService());

        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                () -> processWithdrawal(service, profile(), order()));

        assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertEquals(1, repository.signingClaims);
        assertEquals(1, repository.broadcastUnknownMarks);
        assertEquals(0, repository.lockReleases);
        assertEquals("rpc accepted maybe but returned error", repository.broadcastError);
    }

    private static void processWithdrawal(AccountChainWorkflowService service,
                                          AccountChainProfile profile,
                                          WithdrawalOrderRecord order) throws Exception {
        Method method = AccountChainWorkflowService.class.getDeclaredMethod(
                "processWithdrawal", AccountChainProfile.class, WithdrawalOrderRecord.class);
        method.setAccessible(true);
        method.invoke(service, profile, order);
    }

    private static AccountChainProfile profile() {
        return AccountChainProfile.builder()
                .chain("ETH")
                .network("sepolia")
                .family("evm")
                .nativeSymbol("ETH")
                .withdrawConfirmations(1)
                .enabled(true)
                .build();
    }

    private static WithdrawalOrderRecord order() {
        return WithdrawalOrderRecord.builder()
                .orderNo("wd-test")
                .userId(1L)
                .chain("ETH")
                .assetSymbol("ETH")
                .fromAddress("0xsource")
                .debitAccountId("0xsource")
                .toAddress("0xtarget")
                .amount(BigDecimal.ONE)
                .fee(BigDecimal.ZERO)
                .status("FROZEN")
                .build();
    }

    private static AccountChainWorkflowService service(ChainJdbcRepository repository,
                                                       EvmAccountTransactionService evmService) {
        return new AccountChainWorkflowService(
                repository,
                null,
                null,
                null,
                null,
                evmService,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static final class FakeRepository extends ChainJdbcRepository {
        private final ChainAddressRecord address;
        private int signingClaims;
        private int broadcastUnknownMarks;
        private int lockReleases;
        private String broadcastError;

        private FakeRepository(ChainAddressRecord address) {
            super(null);
            this.address = address;
        }

        @Override
        public Optional<ChainAddressRecord> findChainAddressByAddress(String chain, String assetSymbol, String address) {
            return Optional.of(this.address);
        }

        @Override
        public Optional<ChainAddressRecord> findChainAddressByAddress(String chain, String address) {
            return Optional.of(this.address);
        }

        @Override
        public int claimWithdrawalSigning(String chain, String orderNo, String fromAddress) {
            signingClaims++;
            return 1;
        }

        @Override
        public int markWithdrawalBroadcastUnknown(String chain, String orderNo,
                                                  String fromAddress, String errorMessage) {
            broadcastUnknownMarks++;
            broadcastError = errorMessage;
            return 1;
        }

        @Override
        public boolean releaseLockedBalance(String chain, String assetSymbol, String accountId, BigDecimal amount) {
            lockReleases++;
            return true;
        }
    }

    private static final class FailingEvmService extends EvmAccountTransactionService {
        private FailingEvmService() {
            super(null, null, null, null);
        }

        @Override
        public String sendNative(String chain, ChainAddressRecord from, String toAddress, BigDecimal amount) {
            throw new IllegalStateException("rpc accepted maybe but returned error");
        }
    }
}
