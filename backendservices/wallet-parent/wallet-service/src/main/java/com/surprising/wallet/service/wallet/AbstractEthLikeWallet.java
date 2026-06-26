package com.surprising.wallet.service.wallet;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.client.command.EthLikeCommand;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.pojo.rpc.EthLikeBlock;
import com.surprising.wallet.common.pojo.rpc.EthRawTransaction;
import com.surprising.wallet.common.utils.EthereumUtil;
import com.surprising.wallet.service.asset.AssetRoutingService;
import com.surprising.wallet.service.config.PubKeyConfig;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.service.AddressService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.crypto.ECKey;
import org.ethereum.crypto.EthECKey;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;

/**
 * @author atomex
 * @data 12/04/2018
 */
@Slf4j
@Data
abstract public class AbstractEthLikeWallet extends com.surprising.wallet.service.wallet.AbstractAccountWallet implements IWallet {

    public static final String ETH_ADDRESS_PREFIX = "0x";
    @Autowired
    protected PubKeyConfig pubKeyConfig;
    protected EthLikeCommand command;
    protected Long height = 0L;
    @Autowired
    protected AddressService addressService;
    @Autowired
    protected AssetRoutingService assetRoutingService;
    @Autowired
    protected ChainJdbcRepository chainJdbcRepository;
    private String withdrawAddress;


    /**
     * 上次更新总余额的时间戳
     */
    private long lastUpdateTotalBalance = 0;

    @Override
    protected boolean shouldUpdateTotalBalance() {
        long now = System.currentTimeMillis();
        //6个小时整体刷新余额
        long sixHours = 6 * 60 * 60 * 1000;
        if (now - lastUpdateTotalBalance > sixHours) {
            lastUpdateTotalBalance = now;
            return true;
        } else {
            return false;
        }
    }

    @Override
    public String getWithdrawAddress() {
        return withdrawAddress;
    }

    public void setCommand(EthLikeCommand com) {
        command = com;
    }

    protected RuntimeAsset loadRuntimeAssetByChain(String chain) {
        return assetRoutingService.runtimeAssetByChain(chain);
    }

    /**
     * 生成新地址
     *
     * @param userId 用户id
     * @param biz    业务类型： spot、c2c 等
     */
    @Override
    @Transactional(rollbackFor = Throwable.class)
    public synchronized Address genNewAddress(Long userId, Integer biz) {
        log.info("生成新地址 开始 用户id:{}, 业务线:{}, 币种:{}", userId, biz, getCurrency().name());
        rejectReservedHotAddress(userId, biz);

        RuntimeAsset currency = getCurrency();
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                int index = nextChainAddressIndex(currency, userId, biz);
                ECKey ecKey = deriveAddressKey(currency, userId, biz, index);
                String addressStr = formatDerivedAddress(ecKey);
                Address address = buildRuntimeAddress(currency, userId, biz, index, addressStr);
                chainJdbcRepository.upsertChainAddress(toChainAddressRecord(address, currency));
                log.info("生成新地址 结束 用户id:{}, 业务线:{}, 币种:{} 地址:{}",
                        userId, biz, currency.name(), addressStr);
                return address;
            } catch (DuplicateKeyException e) {
                log.warn("生成地址遇到并发重复, userId={}, biz={}, currency={}, attempt={}",
                        userId, biz, currency.name(), attempt + 1);
            }
        }
        throw new IllegalStateException("failed to allocate a unique address index for " + currency);
    }

    @Override
    public Address deriveAddress(Long userId, Integer biz, int index) {
        RuntimeAsset currency = getCurrency();
        ECKey ecKey = deriveAddressKey(currency, userId, biz, index);
        return buildRuntimeAddress(currency, userId, biz, index, formatDerivedAddress(ecKey));
    }

    private void rejectReservedHotAddress(Long userId, Integer biz) {
        if (HotWalletRules.isDefaultHotUser(userId, biz)) {
            throw new IllegalArgumentException("userId=0,biz=0 is reserved for the unique default hot wallet address");
        }
    }

    private int nextChainAddressIndex(RuntimeAsset currency, Long userId, Integer biz) {
        return chainJdbcRepository.findMaxChainAddressIndex(
                        currency.chain(), currency.assetSymbol(), userId, biz, "DEPOSIT")
                .map(value -> Math.toIntExact(value + 1))
                .orElse(0);
    }

    private ECKey deriveAddressKey(RuntimeAsset currency, Long userId, Integer biz, int index) {
        return pubKeyConfig.NODE2.getChild(44)
                .getChild(currency.getDerivationCoinType())
                .getChild(biz)
                .getChild(userId.intValue())
                .getChild(index)
                .getEcKey();
    }

    protected String formatDerivedAddress(ECKey ecKey) {
        EthECKey ethEcKey = EthECKey.fromPublicOnly(ecKey.getPubKey());
        return ETH_ADDRESS_PREFIX + Hex.toHexString(ethEcKey.getAddress());
    }

    private Address buildRuntimeAddress(RuntimeAsset currency, Long userId, Integer biz, int index, String addressStr) {
        Address address = new Address();
        address.setAddress(addressStr);
        address.setBiz(biz);
        address.setCurrency(currency.getName());
        address.setUserId(userId);
        address.setIndex(index);
        address.setDerivationPath(derivationPath(currency, userId, biz, index));
        return address;
    }

    private String derivationPath(RuntimeAsset currency, Long userId, Integer biz, int index) {
        return String.format("m/44/%d/%d/%d/%d", currency.getDerivationCoinType(), biz, userId, index);
    }

    private ChainAddressRecord toChainAddressRecord(Address address, RuntimeAsset currency) {
        return ChainAddressRecord.builder()
                .chain(currency.chain())
                .assetSymbol(currency.assetSymbol())
                .accountId(address.getAddress())
                .userId(address.getUserId())
                .biz(address.getBiz())
                .addressIndex(address.getIndex().longValue())
                .address(address.getAddress())
                .ownerAddress(address.getAddress())
                .derivationPath(address.getDerivationPath())
                .walletRole("DEPOSIT")
                .enabled(true)
                .build();
    }

    /**
     * 返回当前币种钱包中的余额
     *
     * @return
     */
    @Override
    public BigDecimal getBalance() {
        return getBalance(getCurrency());
    }


    public BigDecimal getBalance(RuntimeAsset currency) {
        String currencyName = currency.getName();
        log.info("get {} Balance begin", currencyName);
        try {
            return chainJdbcRepository.listChainAddresses(currency.chain(), currency.assetSymbol())
                    .parallelStream()
                    .map(address -> getBalance(address.getAddress()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } catch (Throwable e) {
            log.error("get {} Balance error", currencyName, e);

        }
        return BigDecimal.ZERO;

    }

    /**
     * 获得区块链的当前最大高度
     */
    @Override
    public Long getBestHeight() {
        String height = command.getBlockNumber();
        this.height = EthereumUtil.hexToLong(height);
        return this.height;
    }

    @Override
    public List<TransactionDTO> findRelatedTxs(Long height) {
        log.info("{} legacy account block scanner disabled; use DB Asset Model scanner, height={}",
                getCurrency().getName(), height);
        return Collections.emptyList();
    }

    @Transactional(rollbackFor = Throwable.class, isolation = Isolation.READ_UNCOMMITTED)
    public void updateAddressBalance(Set<String> relatedAddress) {
        for (String addressStr : relatedAddress) {
            Address address = searchAddress(addressStr, getCurrency());
            if (ObjectUtils.isEmpty(address)) {
                continue;
            }
            BigDecimal balance = getBalance(addressStr);
            log.debug("{} address {} chain balance {}", getCurrency().getName(), addressStr, balance);
        }
    }

    @Override
    protected void updateWithdrawTXId(String txId, RuntimeAsset currency) {
        super.updateWithdrawTXId(txId, currency);
        updateLegacyAccountDepositStatus(txId, currency);
    }

    protected Address searchAddress(String addressStr, RuntimeAsset currency) {
        if (!StringUtils.hasText(addressStr)) {
            return null;
        }
        return addressService.getAddress(addressStr, currency);
    }


    @Override
    @Transactional(rollbackFor = Throwable.class, isolation = Isolation.READ_UNCOMMITTED)
    public void transfer(String address, RuntimeAsset currency, Date deadline) {
        log.info("{} legacy account transfer job disabled; use collection_record/DB Asset Model flow address={}",
                currency.getName(), address);
    }


    @Override
    protected WithdrawTransaction buildTransaction(WithdrawRecord record) {
        RuntimeAsset currency = resolveRuntimeAsset(record);
        log.warn("{} legacy withdraw buildTransaction disabled; use withdrawal_order/signing_transaction flow",
                currency.getName());
        return null;
    }

    protected RuntimeAsset resolveRuntimeAsset(WithdrawRecord record) {
        return assetRoutingService.runtimeAsset(record.getCurrency());
    }

    protected WithdrawTransaction buildTransaction(WithdrawRecord record, String from, String type) {
        RuntimeAsset currency = resolveRuntimeAsset(record);
        log.warn("{} legacy account buildTransaction disabled; from={} type={}",
                currency.getName(), from, type);
        return null;
    }

    /**
     * 发送签好的原始交易
     */
    @Override
    public String sendRawTransaction(WithdrawTransaction transaction) {
        String result;
        try {
            JSONObject signature = JSONObject.parseObject(transaction.getSignature());

            String raw = signature.getString("rawTransaction");
            result = command.sendRawTransaction(raw);
        } catch (Throwable e) {
            log.error("sendRawTransaction error", e);
            result = "";
        }
        return result;
    }

    /**
     * 获得区块hash
     */
    @Override
    public String getBlockHash(Long height) {
        EthLikeBlock block = command.getBlockByHeight(ETH_ADDRESS_PREFIX + Long.toHexString(height), true);
        String hash = block.getHash();
        return hash;
    }

    public EthRawTransaction getTransaction(String txId) {
        int dashIndex = txId.indexOf("-");
        if (dashIndex > 0) {
            txId = txId.substring(0, dashIndex);
        }
        return command.getTransaction(txId);
    }

    public BigDecimal getBalance(String address) {
        return getBalance(address, getCurrency());
    }

    @Override
    protected BigDecimal getBalance(String address, RuntimeAsset currency) {
        String tranAmount = command.getBalance(address, "latest");
        BigDecimal amount = new BigDecimal(EthereumUtil.hexToBigInteger(tranAmount));
        return amount.divide(getDecimal());
    }

    @Override
    public boolean checkAddress(String addressStr) {
        boolean valid = false;
        if (StringUtils.hasText(addressStr)) {
            try {
                String rex = "^0x[a-fA-F0-9]{40}$";
                if (addressStr.matches(rex)) {
                    valid = true;
                }
            } catch (AddressFormatException e) {
                log.error("{} is not valid", addressStr, e);
            }
        }
        return valid;

    }

    @Override
    public int getConfirm(String txId) {
        EthRawTransaction transaction = getTransaction(txId);
        if (ObjectUtils.isEmpty(transaction)) {
            return -1;

        } else {
            Long blockHeight = EthereumUtil.hexToLong(transaction.getBlockNumber());
            Long bestHeight = getBestHeight();
            Long confirm = bestHeight - blockHeight + 1;
            return confirm.intValue();
        }

    }

    @Override
    public String getTxId(WithdrawTransaction transaction) {
        JSONObject signature = JSONObject.parseObject(transaction.getSignature());
        String raw = signature.getString("rawTransaction");
        return caculateTransactionHash(raw);
    }
}
