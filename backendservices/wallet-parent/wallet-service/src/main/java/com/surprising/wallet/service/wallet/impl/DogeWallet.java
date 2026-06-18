package com.surprising.wallet.service.wallet.impl;

import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.wallet.client.command.DogeCommand;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.service.criteria.AddressExample;
import com.surprising.wallet.service.wallet.AbstractBtcLikeWallet;
import com.surprising.wallet.service.wallet.IWallet;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.dogecoinj.DogeSdk;
import org.libdohj.params.DogecoinMainNetParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;


/**
 * @author lilaizhen
 * @data 27/03/2018
 */
@Slf4j
@Component
public class DogeWallet extends AbstractBtcLikeWallet implements IWallet {

    final
    DogeCommand command;
    private Bip32Node dogeNode;
    @Value("${atomex.doge.pubkey}")
    private String dogePubkey;

    public DogeWallet(DogeCommand command) {
        this.command = command;
    }

    @PostConstruct
    public void init() {
        super.setCommand(command);
        dogeNode = Bip32Node.decode(dogePubkey);
    }

    @Override
    public BigDecimal getDecimal() {
        return getCurrency().getDecimal();
    }

    @Override
    public Long getBestHeight() {
        return super.getBestHeight();
    }

    @Override
    public NetworkParameters getNetworkParameters() {
        return DogecoinMainNetParams.get();
    }

    @Override
    public CurrencyEnum getCurrency() {
        return CurrencyEnum.DOGE;
    }

    @Override
    public Address genNewAddress(Long userId, Integer biz) {
        AddressExample example = new AddressExample();
        example.createCriteria().andUserIdEqualTo(userId).andBizEqualTo(biz);

        ShardTable table = ShardTable.builder().prefix(getCurrency().getName()).build();
        List<Address> addressList = addressService.getByExample(example, table);
        int index = 0;

        //获取该userId在biz业务线下面已经生成了多少地址
        if (!CollectionUtils.isEmpty(addressList)) {

            Optional<Address> maxAddress = addressList.stream().max(Comparator.comparing(Address::getIndex));
            index = maxAddress.get().getIndex() + 1;
        }
        /*
         * tron 的前缀是41
         * hd的公钥推导path: bip44-currency-biz-userId-index
         */
        CurrencyEnum currency = getCurrency();
        ECKey ecKey = dogeNode.getChild(44).getChild(currency.getIndex()).getChild(biz).getChild(userId.intValue()).getChild(index).getEcKey();
        String newAddress = DogeSdk.getNewAddress(ecKey);
        Address address = Address.builder()
                .address(newAddress)
                .biz(biz)
                .currency(getCurrency().getName())
                .userId(userId)
                .index(index).build();
        addressService.add(address, table);
        log.info("genNewAddress, userId:{}, biz:{}, currency:{} end", userId, biz, getCurrency().name());
        return address;
    }

    @Override
    public boolean checkAddress(String addressStr) {
        return DogeSdk.checkAddress(addressStr);
    }

}
