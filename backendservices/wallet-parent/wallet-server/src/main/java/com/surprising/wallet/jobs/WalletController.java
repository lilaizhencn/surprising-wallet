package com.surprising.wallet.web.controller;

import com.alibaba.fastjson.JSONObject;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.commons.support.model.ResponseResult;
import com.surprising.commons.support.util.ResultUtils;
import com.surprising.wallet.common.currency.BizEnum;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.AddressDto;
import com.surprising.wallet.common.pojo.*;
import com.surprising.wallet.service.criteria.AddressExample;
import com.surprising.wallet.service.criteria.CurrencyBalanceExample;
import com.surprising.wallet.service.criteria.UtxoTransactionExample;
import com.surprising.wallet.service.service.AddressService;
import com.surprising.wallet.service.service.CurrencyBalanceService;
import com.surprising.wallet.service.service.UtxoTransactionService;
import com.surprising.wallet.service.wallet.IWallet;
import com.surprising.wallet.service.wallet.WalletContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
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
    private final UtxoTransactionService utxoService;

    @Value("${atomex.wallet.pubKey1}")
    private String pubKey1;
    @Value("${atomex.wallet.pubKey2}")
    private String pubKey2;
    @Value("${atomex.wallet.pubKey3}")
    private String pubKey3;

    public WalletController(WalletContext context, CurrencyBalanceService balanceService,
                            AddressService addressService, UtxoTransactionService utxoService) {
        this.context = context;
        this.balanceService = balanceService;
        this.addressService = addressService;
        this.utxoService = utxoService;
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
            addressDto.setChildId(address.getIndex());
            addressDto.setPath(address.getDerivationPath());
            addressDto.setNetwork(address.getNetwork());
            addressDto.setScriptType(address.getScriptType());
            addressDto.setRedeemScript(address.getRedeemScript());
            addressDto.setWitnessScript(address.getWitnessScript());
            addressDto.setPublicKeys(address.getPublicKeys());
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

    // ================================================================
    // Wallet Dashboard APIs
    // ================================================================

    /** GET /wallet/v1/biz-types — available business line codes */
    @GetMapping("/biz-types")
    public ResponseResult<List<Map<String, Object>>> getBizTypes() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (BizEnum b : BizEnum.values()) {
            Map<String, Object> m = new HashMap<>();
            m.put("code", b.getIndex());
            m.put("name", b.getName());
            list.add(m);
        }
        return ResultUtils.success(list);
    }

    /** GET /wallet/v1/key-info — multisig key pairs (xpub + xprv) */
    @GetMapping("/key-info")
    public ResponseResult<Map<String, Object>> getKeyInfo() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, String>> keys = new ArrayList<>();
            addKeyInfo(keys, "KEY 1 (Hot - Sig1)", pubKey1, "sig1 首签 (hot wallet)", "m/44/1/0/0/0");
            addKeyInfo(keys, "KEY 2 (Cold - Sig2)", pubKey2, "sig2 二次签名 (cold wallet)", "m/44/1/0/0/0");
            addKeyInfo(keys, "KEY 3 (Offline Backup)", pubKey3, "离线备份", "m/44/1/0/0/0");
            result.put("keys", keys);
            result.put("scriptType", "P2WSH 2-of-3 Multisig (Native SegWit)");
            result.put("network", "Bitcoin TestNet3");
        } catch (Exception e) {
            log.error("getKeyInfo error", e);
            return ResultUtils.failure("获取密钥信息失败");
        }
        return ResultUtils.success(result);
    }

    private void addKeyInfo(List<Map<String, String>> list, String label, String xpub, String role, String path) {
        Map<String, String> k = new HashMap<>();
        k.put("label", label);
        k.put("xpub", xpub);
        k.put("xprv", "");
        k.put("role", role);
        k.put("derivationPath", path);
        list.add(k);
    }

    /** GET /wallet/v1/hot-info?currency=BTC — hot wallet details */
    @GetMapping("/hot-info")
    public ResponseResult<Map<String, Object>> getHotWalletInfo(
            @RequestParam(value = "currency", defaultValue = "1") Integer currency) {
        Map<String, Object> result = new HashMap<>();
        try {
            CurrencyEnum coin = CurrencyEnum.parseValue(currency);
            coin = CurrencyEnum.toMainCurrency(coin);

            // Hot wallet address at userId=0, biz=0, index=0
            AddressExample example = new AddressExample();
            example.createCriteria().andUserIdEqualTo(0L).andBizEqualTo(0);
            ShardTable table = ShardTable.builder().prefix(coin.getName()).build();
            Optional<Address> addrOpt = addressService.getOneByExample(example, table);

            if (addrOpt.isPresent()) {
                Address addr = addrOpt.get();
                result.put("address", addr.getAddress());
                result.put("childId", addr.getIndex());
                result.put("path", addr.getDerivationPath());
                result.put("scriptType", addr.getScriptType());
                result.put("witnessScript", addr.getWitnessScript());
                result.put("publicKeys", addr.getPublicKeys());
                result.put("currency", coin.getName());
            }

            // Balance
            CurrencyBalanceExample balEx = new CurrencyBalanceExample();
            balEx.createCriteria().andCurrencyIndexEqualTo(coin.getIndex());
            Optional<CurrencyBalance> bal = balanceService.getOneByExample(balEx);
            result.put("balance", bal.map(CurrencyBalance::getBalance).orElse(BigDecimal.ZERO));

            // Computed: derivation chain, witness script from 3 xpubs
            result.put("network", "Bitcoin TestNet3");
            result.put("scriptType", "P2WSH 2-of-3 Multisig");
        } catch (Exception e) {
            log.error("getHotWalletInfo error currency={}", currency, e);
            return ResultUtils.failure("获取热钱包信息失败");
        }
        return ResultUtils.success(result);
    }

    /** GET /wallet/v1/addresses — paginated user addresses */
    @GetMapping("/addresses")
    public ResponseResult<List<AddressDto>> getAddresses(
            @RequestParam(value = "currency", required = false) Integer currency,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "biz", required = false) Integer biz) {
        try {
            CurrencyEnum coin = currency != null ? CurrencyEnum.parseValue(currency) : CurrencyEnum.BTC;
            coin = CurrencyEnum.toMainCurrency(coin);
            ShardTable table = ShardTable.builder().prefix(coin.getName()).build();

            AddressExample example = new AddressExample();
            var criteria = example.createCriteria();
            criteria.andUserIdGreaterThan(0L); // exclude hot wallet
            if (userId != null) criteria.andUserIdEqualTo(userId);
            if (biz != null) criteria.andBizEqualTo(biz);
            example.setOrderByClause("id desc");

            com.surprising.common.mybatis.pager.PageInfo pageInfo = new com.surprising.common.mybatis.pager.PageInfo();
            pageInfo.setPageSize(200);
            pageInfo.setStartIndex(0);

            List<Address> addrs = addressService.getByPage(pageInfo, example, table);
            List<AddressDto> dtos = addrs.stream().map(a -> {
                AddressDto dto = new AddressDto();
                dto.setUserId(a.getUserId());
                dto.setBiz(a.getBiz());
                dto.setAddress(a.getAddress());
                dto.setChildId(a.getIndex());
                dto.setPath(a.getDerivationPath());
                dto.setNetwork(a.getNetwork());
                dto.setScriptType(a.getScriptType());
                dto.setRedeemScript(a.getRedeemScript());
                dto.setWitnessScript(a.getWitnessScript());
                dto.setPublicKeys(a.getPublicKeys());
                return dto;
            }).collect(Collectors.toList());

            return ResultUtils.success(dtos);
        } catch (Exception e) {
            log.error("getAddresses error", e);
            return ResultUtils.failure("获取地址列表失败");
        }
    }

    /** GET /wallet/v1/addresses/transactions — transactions for an address */
    @GetMapping("/addresses/transactions")
    public ResponseResult<List<UtxoTransaction>> getAddressTransactions(
            @RequestParam(value = "address") String address,
            @RequestParam(value = "currency") Integer currency) {
        try {
            CurrencyEnum coin = CurrencyEnum.parseValue(currency);
            ShardTable table = ShardTable.builder().prefix(coin.getName()).build();

            UtxoTransactionExample example = new UtxoTransactionExample();
            example.createCriteria().andAddressEqualTo(address);
            example.setOrderByClause("id desc");

            com.surprising.common.mybatis.pager.PageInfo pageInfo = new com.surprising.common.mybatis.pager.PageInfo();
            pageInfo.setPageSize(50);
            pageInfo.setStartIndex(0);

            List<UtxoTransaction> txs = utxoService.getByPage(pageInfo, example, table);
            return ResultUtils.success(txs);
        } catch (Exception e) {
            log.error("getAddressTransactions error address={}", address, e);
            return ResultUtils.failure("获取交易列表失败");
        }
    }
}
