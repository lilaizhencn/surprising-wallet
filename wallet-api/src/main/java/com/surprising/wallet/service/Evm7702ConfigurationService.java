package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.Evm7702ConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 新批次使用启用配置及其经过校验的中继密钥路径；历史回执只读取原配置版本。
 */
@Service
@RequiredArgsConstructor
public class Evm7702ConfigurationService {

    private final Evm7702ConfigRepository configurations;
    private final ChainAddressRepository addresses;

    public Resolved requireActive(String chain, String network) {
        var config = configurations.find(
                chain,
                network,
                "ACTIVE",
                null).stream().findFirst().orElseThrow(() -> new IllegalStateException("EIP-7702 ACTIVE configuration is missing for " + chain + "/" + network));
        var relayer = addresses.findById(config.relayerChainAddressId()).map(ChainAddressRepository::mapRow).orElseThrow(() -> new IllegalStateException("configured relayer chain address is missing"));
        if (config.relayerAddress() == null || !config.relayerAddress().equalsIgnoreCase(relayer.getAddress())) {
            throw new IllegalStateException("configured relayer address does not match chain_address key path");
        }
        if (!Boolean.TRUE.equals(relayer.getEnabled())) {
            throw new IllegalStateException("configured EIP-7702 relayer chain_address is disabled");
        }
        return new Resolved(config, relayer);
    }

    public record Resolved(Evm7702ConfigRepository.Configuration config, ChainAddressRecord relayer) {
    }
}
