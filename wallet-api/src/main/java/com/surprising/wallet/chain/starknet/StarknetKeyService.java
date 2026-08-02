package com.surprising.wallet.chain.starknet;

import com.swmansion.starknet.crypto.StarknetCurve;
import com.swmansion.starknet.data.ContractAddressCalculator;
import com.swmansion.starknet.data.types.Felt;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Starknet 账户地址和私钥派生服务。
 *
 * <p>Starknet 地址不是公钥摘要，而是账户合约的确定性地址。服务使用项目现有
 * sig2 BIP32 根密钥派生 Stark Curve 私钥，再使用链配置中的账户 class hash、
 * 公钥 calldata 和公钥 salt 计算 counterfactual 地址。账户 class hash 必须由
 * 运维按网络配置，禁止在业务代码中隐式替换账户合约版本。</p>
 */
@Service
public class StarknetKeyService {

    /** Starknet 地址的最大合法范围。 */
    private static final BigInteger ADDRESS_UPPER_BOUND = BigInteger.ONE.shiftLeft(251).subtract(BigInteger.valueOf(256));
    /** Starknet 地址的文本格式。 */
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("^0x[0-9a-fA-F]{1,64}$");

    /** 钱包密钥材料提供者。 */
    private final WalletKeyMaterialProvider keyMaterial;

    /** 构造 Starknet 密钥服务。 */
    public StarknetKeyService(WalletKeyMaterialProvider keyMaterial) {
        this.keyMaterial = keyMaterial;
    }

    /** 派生指定租户坐标对应的 Starknet counterfactual 账户地址。 */
    public DerivedKey derive(AccountChainProfile profile, long userId, int biz, long addressIndex) {
        if (profile == null || !"starknet".equalsIgnoreCase(profile.getFamily())) {
            throw new IllegalArgumentException("Starknet profile is required");
        }
        Felt classHash = parseFelt(profile.getAccountClassHash(), "accountClassHash");
        Bip32Node node = keyMaterial.sig2Root()
                .getChild(44)
                .getChild(ChainType.derivationCoinType(profile.getChain(), profile.getBip44CoinType()))
                .getChild(biz)
                .getChild(Math.toIntExact(userId))
                .getChild(Math.toIntExact(addressIndex));
        BigInteger privateValue = new BigInteger(1, node.getEcKey().getPrivKeyBytes())
                .mod(StarknetCurve.CURVE_ORDER);
        if (privateValue.signum() == 0) {
            throw new IllegalStateException("derived Starknet private key is zero");
        }
        Felt privateKey = new Felt(privateValue);
        Felt publicKey = StarknetCurve.getPublicKey(privateKey);
        Felt address = ContractAddressCalculator.calculateAddressFromHash(
                classHash, List.of(publicKey), publicKey);
        if (address.getValue().compareTo(ADDRESS_UPPER_BOUND) >= 0) {
            throw new IllegalStateException("derived Starknet address is outside the contract address range");
        }
        return new DerivedKey(privateKey, publicKey, address, "m/44/"
                + ChainType.derivationCoinType(profile.getChain(), profile.getBip44CoinType())
                + "/" + biz + "/" + userId + "/" + addressIndex);
    }

    /** 校验 Starknet contract address 的 felt 范围和文本格式。 */
    public static boolean isValidAddress(String address) {
        if (address == null || !ADDRESS_PATTERN.matcher(address.trim()).matches()) {
            return false;
        }
        try {
            BigInteger value = Felt.fromHex(address.trim()).getValue();
            return value.compareTo(ADDRESS_UPPER_BOUND) < 0;
        } catch (RuntimeException error) {
            return false;
        }
    }

    /** 将 Starknet 地址标准化为小写 felt 十六进制表示。 */
    public static String normalizeAddress(String address) {
        if (!isValidAddress(address)) {
            throw new IllegalArgumentException("invalid Starknet address: " + address);
        }
        return Felt.fromHex(address.trim()).hexString().toLowerCase(Locale.ROOT);
    }

    /** 解析配置中的 felt。 */
    public static Felt parseFelt(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is required for Starknet");
        }
        try {
            return Felt.fromHex(value.trim());
        } catch (RuntimeException error) {
            throw new IllegalStateException(field + " must be a valid felt hex value", error);
        }
    }

    /** Starknet 派生结果。 */
    public record DerivedKey(Felt privateKey, Felt publicKey, Felt address, String derivationPath) {
        /** 转换为通用 chain_address 记录。 */
        public ChainAddressRecord toAddressRecord(AccountChainProfile profile, long userId, int biz,
                                                   long addressIndex, String walletRole) {
            String normalized = address.hexString().toLowerCase(Locale.ROOT);
            return ChainAddressRecord.builder()
                    .chain(profile.getChain())
                    .assetSymbol(profile.getNativeSymbol())
                    .accountId(normalized)
                    .userId(userId)
                    .biz(biz)
                    .addressIndex(addressIndex)
                    .address(normalized)
                    .ownerAddress(normalized)
                    .derivationPath(derivationPath)
                    .walletRole(walletRole)
                    .enabled(true)
                    .build();
        }
    }
}
