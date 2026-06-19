package com.surprising.wallet.web.controller;

import com.alibaba.fastjson.JSONObject;
import com.surprising.common.mybatis.sharding.ShardTable;
import com.surprising.commons.support.model.ResponseResult;
import com.surprising.commons.support.util.ResultUtils;
import com.surprising.wallet.common.currency.BizEnum;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.common.dto.AddressDto;
import com.surprising.wallet.common.pojo.*;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.service.criteria.AddressExample;
import com.surprising.wallet.service.criteria.CurrencyBalanceExample;
import com.surprising.wallet.service.criteria.UtxoTransactionExample;
import com.surprising.wallet.service.service.AddressService;
import com.surprising.wallet.service.service.CurrencyBalanceService;
import com.surprising.wallet.service.service.UtxoTransactionService;
import com.surprising.wallet.service.wallet.IWallet;
import com.surprising.wallet.service.wallet.WalletContext;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.SegwitMultiSignAddressGenerator;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
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
            addressDto.setPath(String.format("m/44'/%d'/%d/%d/%d",
                    coin.getIndex(), biz, userId, address.getIndex()));
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
            String pub1 = System.getProperty("atomex.wallet.pubKey1", "");
            String pub2 = System.getProperty("atomex.wallet.pubKey2", "");
            String pub3 = System.getProperty("atomex.wallet.pubKey3", "");

            List<Map<String, String>> keys = new ArrayList<>();
            addKeyInfo(keys, "KEY 1 (Hot — Sig1)", pub1, "sig1 首签 (hot wallet)", "m/44'/1'/0/0/0");
            addKeyInfo(keys, "KEY 2 (Cold — Sig2)", pub2, "sig2 二次签名 (cold wallet)", "m/44'/1'/0/0/0");
            addKeyInfo(keys, "KEY 3 (Offline Backup)", pub3, "离线备份 (保险柜)", "m/44'/1'/0/0/0");
            result.put("keys", keys);
            result.put("scriptType", "P2WSH 2-of-3 Multisig (Native SegWit)");
            result.put("network", Constants.NET_PARAMS.getId().contains("test") ? "Testnet" : "Mainnet");
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
                result.put("path", String.format("m/44'/%d'/0/0/%d", coin.getIndex(), addr.getIndex()));
                result.put("currency", coin.getName());
            }

            // Balance
            CurrencyBalanceExample balEx = new CurrencyBalanceExample();
            balEx.createCriteria().andCurrencyIndexEqualTo(coin.getIndex());
            Optional<CurrencyBalance> bal = balanceService.getOneByExample(balEx);
            result.put("balance", bal.map(CurrencyBalance::getBalance).orElse(BigDecimal.ZERO));

            // Computed: derivation chain, witness script from 3 xpubs
            result.put("network", Constants.NET_PARAMS.getId().contains("test") ? "Testnet" : "Mainnet");
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
            final int coinIdx = coin.getIndex();

            List<AddressDto> dtos = addrs.stream().map(a -> {
                AddressDto dto = new AddressDto();
                dto.setUserId(a.getUserId());
                dto.setBiz(a.getBiz());
                dto.setAddress(a.getAddress());
                dto.setChildId(a.getIndex());
                dto.setPath(String.format("m/44'/%d'/%d/%d/%d",
                        coinIdx, a.getBiz(), a.getUserId(), a.getIndex()));
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
