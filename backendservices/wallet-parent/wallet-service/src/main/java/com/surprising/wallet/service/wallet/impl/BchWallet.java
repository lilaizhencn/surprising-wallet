package com.surprising.wallet.service.wallet.impl;

import com.surprising.wallet.client.command.BchCommand;
import com.surprising.wallet.common.chain.BitcoinLikeChainProfile;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashAddressCodec;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashNetworkParameters;
import com.surprising.wallet.service.config.PubKeyConfig;
import com.surprising.wallet.service.wallet.AbstractBtcLikeWallet;
import jakarta.annotation.PostConstruct;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;

@Component
public class BchWallet extends AbstractBtcLikeWallet {
    @Autowired
    private BchCommand bchCommand;

    private BitcoinLikeChainProfile profile;
    private RuntimeAsset currency;

    @PostConstruct
    public void init() {
        setCommand(bchCommand);
        profile = chainJdbcRepository.findProfileByChain("BCH")
                .flatMap(enabled -> chainJdbcRepository.findBitcoinLikeProfile("BCH", enabled.getNetwork()))
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for BCH"));
        currency = loadBitcoinLikeRuntimeAsset("BCH", profile.getNetwork());
    }

    @Override
    public RuntimeAsset getCurrency() {
        return currency;
    }

    @Override
    public NetworkParameters getNetworkParameters() {
        if (isMainnet()) {
            return BitcoinCashNetworkParameters.mainnet();
        }
        return isRegtest()
                ? BitcoinCashNetworkParameters.regtest()
                : BitcoinCashNetworkParameters.testnet();
    }

    @Override
    protected int getBip44CoinType() {
        return profile.getBip44CoinType();
    }

    @Override
    public long getDepositConfirmationThreshold() {
        return profile.getDepositConfirmations();
    }

    @Override
    public long getDustThresholdAtomic() {
        return profile.getDustThreshold() == null
                ? com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy.DUST_THRESHOLD_SAT
                : profile.getDustThreshold();
    }

    @Override
    protected long getWithdrawConfirmationThreshold() {
        return profile.getWithdrawConfirmations();
    }

    @Override
    protected Address buildAddress(Long userId, Integer biz, int index) {
        PubKeyConfig.AddressMetadata metadata =
                pubKeyConfig.genLegacyThreeTwoAddressMetadata(
                        getNetworkParameters(), getBip44CoinType(), userId.intValue(), biz, index);
        BitcoinCashNetworkParameters params =
                (BitcoinCashNetworkParameters) getNetworkParameters();
        String cashAddress = BitcoinCashAddressCodec.fromLegacy(
                LegacyAddress.fromBase58(params, metadata.getAddress()), params.cashPrefix());
        return Address.builder()
                .address(cashAddress)
                .network(getNetworkName())
                .scriptType("P2SH")
                .redeemScript(metadata.getRedeemScript())
                .witnessScript("")
                .derivationPath(metadata.getPath())
                .publicKeys(metadata.getPublicKeys())
                .biz(biz)
                .currency("bch")
                .userId(userId)
                .index(index)
                .balance(BigDecimal.ZERO)
                .nonce(0)
                .status((byte) Constants.WAITING)
                .createDate(Date.from(Instant.now()))
                .updateDate(Date.from(Instant.now()))
                .build();
    }

    @Override
    protected String normalizeScannedAddress(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        BitcoinCashNetworkParameters params =
                (BitcoinCashNetworkParameters) getNetworkParameters();
        String candidate = address.trim();
        try {
            BitcoinCashAddressCodec.Decoded decoded =
                    BitcoinCashAddressCodec.decode(params.cashPrefix(), candidate);
            return BitcoinCashAddressCodec.encode(
                    params.cashPrefix(), decoded.type(), decoded.hash());
        } catch (IllegalArgumentException ignored) {
            // RPC providers may return legacy addresses. Non-address outputs and
            // addresses from another BCH network must not abort the block scan.
        }
        try {
            return BitcoinCashAddressCodec.fromLegacy(
                    LegacyAddress.fromBase58(params, candidate), params.cashPrefix());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Override
    public boolean checkAddress(String address) {
        return normalizeScannedAddress(address) != null;
    }

    private boolean isMainnet() {
        return "main".equalsIgnoreCase(profile.getNetwork()) || "mainnet".equalsIgnoreCase(profile.getNetwork());
    }

    private boolean isRegtest() {
        return "regtest".equalsIgnoreCase(profile.getNetwork());
    }
}
