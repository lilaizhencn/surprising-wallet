package com.surprising.wallet.service.config;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WalletRuntimeConfigService {
    public static final String TASK_SCAN = "scan";    public static final String TASK_WITHDRAW = "withdraw";    public static final String TASK_COLLECTION = "collection";    public static final String TASK_TRANSFER = "transfer";    public static final String WITHDRAWAL_ADMIN_APPROVAL_REQUIRED = "withdrawal.admin.approval.required";    private final ChainJdbcRepository repository;
    public boolean isTaskEnabled(AssetRuntimeMetadata asset, String task) {
        return asset != null && isTaskEnabled(asset.chain(), task);
    }
    public boolean isTaskEnabled(String chain, String task) {
        if (!repository.systemBoolean("global.all.enabled", true)) {
            return false;
        }
        if (!repository.systemBoolean("global." + task + ".enabled", true)) {
            return false;
        }
        return repository.findProfileByChain(chain)
                .map(profile -> chainTaskEnabled(profile, task))
                .orElse(false);
    }
    public boolean isGlobalTaskEnabled(String task) {
        return repository.systemBoolean("global.all.enabled", true)
                && repository.systemBoolean("global." + task + ".enabled", true);
    }
    public boolean isGlobalEnabled() {
        return repository.systemBoolean("global.all.enabled", true);
    }
    public boolean isWithdrawalAdminApprovalRequired() {
        return repository.systemBoolean(WITHDRAWAL_ADMIN_APPROVAL_REQUIRED, false);
    }
    public void requireTaskEnabled(String chain, String task, String operation) {
        if (isTaskEnabled(chain, task)) {
            return;
        }
        throw new IllegalStateException("wallet runtime switch disabled: chain="
                + chain + " task=" + task + " operation=" + operation);
    }
    public long scanStartHeight(AssetRuntimeMetadata asset) {
        return scanStartHeight(asset.chain());
    }
    public long scanStartHeight(String chain) {
        return repository.findProfileByChain(chain)
                .map(AccountChainProfile::getScanStartHeight)
                .filter(value -> value > 0)
                .orElse(0L);
    }
    public long scanMaxBlocksPerRun(AssetRuntimeMetadata asset) {
        return scanMaxBlocksPerRun(asset.chain());
    }
    public long scanMaxBlocksPerRun(String chain) {
        return repository.findProfileByChain(chain)
                .map(AccountChainProfile::getScanMaxBlocksPerRun)
                .filter(value -> value > 0)
                .orElse(0L);
    }
    private boolean chainTaskEnabled(AccountChainProfile profile, String task) {
        return switch (task) {
            case TASK_SCAN -> Boolean.TRUE.equals(profile.getScanEnabled());
            case TASK_WITHDRAW -> Boolean.TRUE.equals(profile.getWithdrawEnabled());
            case TASK_COLLECTION -> Boolean.TRUE.equals(profile.getCollectionEnabled());
            case TASK_TRANSFER -> Boolean.TRUE.equals(profile.getTransferEnabled());
            default -> false;
        };
    }
}
