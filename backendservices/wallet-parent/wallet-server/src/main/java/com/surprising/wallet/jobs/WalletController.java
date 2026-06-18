package com.surprising.wallet.web.controller;

import com.alibaba.fastjson.JSONObject;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.commons.support.model.ResponseResult;
import com.surprising.commons.support.util.ResultUtils;
import com.surprising.wallet.common.currency.BizEnum;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.AddressDto;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.CurrencyBalance;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.criteria.AddressExample;
import com.surprising.wallet.service.criteria.CurrencyBalanceExample;
import com.surprising.wallet.service.service.AddressService;
import com.surprising.wallet.service.service.CurrencyBalanceService;
import com.surprising.wallet.service.wallet.IWallet;
import com.surprising.wallet.service.wallet.WalletContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author atomex
 */
@Slf4j
@RestController
@RequestMapping("/wallet/v1")
public class WalletController {

    private final WalletContext context;

    private final CurrencyBalanceService balanceService;

    private final AddressService addressService;

    public WalletController(WalletContext context, CurrencyBalanceService balanceService, AddressService addressService) {
        this.context = context;
        this.balanceService = balanceService;
        this.addressService = addressService;
    }

    @PostMapping("/address")
    public ResponseResult<AddressDto> genNewAddress(@RequestParam(value = "currency") Integer currency,
                                                    @RequestParam(value = "userId") Long userId,
                                                    @RequestParam(value = "biz") Integer biz) {
        AddressDto addressDto = new AddressDto();
        try {
            CurrencyEnum coin = CurrencyEnum.parseValue(currency);
            BizEnum.parseBiz(biz);
            coin = CurrencyEnum.toMainCurrency(coin);
            IWallet wallet = context.getWallet(coin);

            Address address = wallet.genNewAddress(userId, biz);
            addressDto.setUserId(userId);
            addressDto.setBiz(biz);
            addressDto.setAddress(address.getAddress());
        } catch (Throwable e) {
            log.error("生成新地址异常 用户id:{} 币种:{} 业务线:{}", userId, currency, biz, e);
            return ResultUtils.failure("生成新地址异常");
        }
        return ResultUtils.success(addressDto);

    }

    @GetMapping("/address/valid")
    public ResponseResult checkAddressValid(@RequestParam(value = "currency") Integer currency,
                                            @RequestParam(value = "address") String address) {
        JSONObject json = new JSONObject();
        try {
            CurrencyEnum coin = CurrencyEnum.parseValue(currency);
            IWallet wallet = context.getWallet(coin);
            boolean valid = wallet.checkAddress(address);
            json.put("address", address);
            json.put("valid", valid);
        } catch (Throwable e) {
            log.error("校验地址异常", e);
            return ResultUtils.failure("校验地址异常");
        }
        return ResultUtils.success(json);

    }

    @GetMapping("/balance")
    public ResponseResult genBalance(@RequestParam(value = "currency") Integer currency) {

        JSONObject json = new JSONObject();
        try {
            CurrencyEnum coin = CurrencyEnum.parseValue(currency);
            CurrencyBalanceExample example = new CurrencyBalanceExample();
            example.createCriteria().andCurrencyIndexEqualTo(coin.getIndex());
            CurrencyBalance balance = balanceService.getOneByExample(example).get();
            if (ObjectUtils.isEmpty(balance)) {
                json.put("balance", BigDecimal.ZERO);
            } else {
                json.put("balance", balance.getBalance());
            }

        } catch (Throwable e) {
            log.error("查询币种余额异常", e);
            return ResultUtils.failure("查询币种余额异常");
        }
        return ResultUtils.success(json);

    }

    @PostMapping(value = "/address/need/{currency}")
    public ResponseResult<?> postNeedAddress(@PathVariable(value = "currency") Integer currency,
                                             @RequestBody List<String> addresses) {


        try {
            CurrencyEnum coin = CurrencyEnum.parseValue(currency);
            if (!CollectionUtils.isEmpty(addresses)) {
                List<Address> res = addresses.parallelStream().map((addr) -> {
                    String[] tmp = addr.split(":");
                    return Address.builder()
                            .address(tmp[0]).index(Integer.parseInt(tmp[1])).userId(-1L).balance(BigDecimal.ZERO)
                            .currency(coin.getName()).biz(0).nonce(0).status((byte) Constants.WAITING)
                            .createDate(Date.from(Instant.now()))
                            .updateDate(Date.from(Instant.now()))
                            .build();
                }).collect(Collectors.toList());
                ShardTable table = ShardTable.builder().prefix(coin.getName()).build();
                addressService.batchAddOnDuplicateKey(res, table);

            } else {
                return ResultUtils.failure("postNeedAddress error: addresses is empty");
            }

            return ResultUtils.success();

        } catch (Throwable e) {
            log.error("postNeedAddress error", e);
            return ResultUtils.failure("postNeedAddress error");
        }


    }


    @GetMapping(value = "/address/need/{currency}")
    public ResponseResult<?> getNeedAddressCount(@PathVariable(value = "currency") Integer currency) {
        JSONObject json = new JSONObject();
        try {
            CurrencyEnum coin = CurrencyEnum.parseValue(currency);
            ShardTable table = ShardTable.builder().prefix(coin.getName()).build();
            AddressExample example = new AddressExample();
            example.createCriteria().andUserIdEqualTo(-1L);
            example.setOrderByClause("id desc");

            Address address = addressService.getOneByExample(example, table).get();
            int maxIndex = 0;
            if (!ObjectUtils.isEmpty(address)) {
                maxIndex = address.getIndex();
            }

            int count = addressService.countByExam(example, table);

            json.put("currency", coin.getName());
            json.put("count", count);
            json.put("index", maxIndex + 1);

        } catch (Throwable e) {
            log.error("getNeedAddressCount error", e);
            return ResultUtils.failure("getNeedAddressCount error");
        }
        return ResultUtils.success(json);
    }

    @GetMapping("/balance/all")
    public ResponseResult<List<CurrencyBalance>> genAllBalance() {
        try {
            List<CurrencyBalance> allCurrencyBalance = balanceService.getAll();
            return ResultUtils.success(allCurrencyBalance);
        } catch (Throwable e) {
            log.error("获取所有余额异常", e);
            return ResultUtils.failure("获取所有余额异常");
        }
    }
}
