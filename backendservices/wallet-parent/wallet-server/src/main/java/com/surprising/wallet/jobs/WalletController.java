package com.surprising.wallet.web.controller;

import com.alibaba.fastjson.JSONObject;
import com.surprising.commons.support.model.ResponseResult;
import com.surprising.commons.support.util.ResultUtils;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.chain.LedgerBalanceRecord;
import com.surprising.wallet.common.chain.WalletPublicKey;
import com.surprising.wallet.common.currency.BizEnum;
import com.surprising.wallet.common.dto.AddressDto;
import com.surprising.wallet.common.pojo.*;
import com.surprising.wallet.service.chain.BlockchainRuntimeService;
import com.surprising.wallet.service.chain.BlockchainRuntimeService.RuntimeChain;
import com.surprising.wallet.service.config.WalletRuntimeConfigService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author atomex
 */
@Slf4j
@RestController
@RequestMapping("/wallet/v1")
@CrossOrigin(
        origins = {"http://localhost:5173", "http://127.0.0.1:5173", "https://tokdou.com", "https://www.tokdou.com"},
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS}
)
public class WalletController {

    private final BlockchainRuntimeService blockchainRuntimeService;
    private final ChainJdbcRepository chainJdbcRepository;
    private final WalletRuntimeConfigService runtimeConfigService;

    public WalletController(BlockchainRuntimeService blockchainRuntimeService,
                            ChainJdbcRepository chainJdbcRepository,
                            WalletRuntimeConfigService runtimeConfigService) {
        this.blockchainRuntimeService = blockchainRuntimeService;
        this.chainJdbcRepository = chainJdbcRepository;
        this.runtimeConfigService = runtimeConfigService;
    }

    @PostMapping("/address")
    public ResponseResult<AddressDto> genNewAddress(@RequestParam(value = "chain") String chain,
                                                    @RequestParam(value = "userId") Long userId,
                                                    @RequestParam(value = "biz") Integer biz) {
        AddressDto addressDto = new AddressDto();
        try {
            if (HotWalletRules.isDefaultHotUser(userId, biz)) {
                return ResultUtils.failure("userId=0,biz=0 是每条链默认热提钱包保留组合，不能通过创建充值地址接口生成");
            }
            if (!runtimeConfigService.isGlobalEnabled()) {
                return ResultUtils.failure("项目总开关 global.all.enabled 已关闭，不能创建新的充值地址");
            }
            BizEnum.parseBiz(biz);

            Address address = blockchainRuntimeService.generateDepositAddress(chain, userId, biz);
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
            log.error("生成新地址异常 用户id:{} 链:{} 业务线:{}", userId, chain, biz, e);
            return ResultUtils.failure("生成新地址异常");
        }
        return ResultUtils.success(addressDto);

    }

    @GetMapping("/address/valid")
    public ResponseResult checkAddressValid(@RequestParam(value = "chain") String chain,
                                            @RequestParam(value = "address") String address) {
        JSONObject json = new JSONObject();
        try {
            boolean valid = blockchainRuntimeService.checkAddress(chain, address);
            json.put("address", address);
            json.put("valid", valid);
        } catch (Throwable e) {
            log.error("校验地址异常", e);
            return ResultUtils.failure("校验地址异常");
        }
        return ResultUtils.success(json);

    }

    @GetMapping("/balance")
    public ResponseResult genBalance(@RequestParam(value = "chain") String chain) {

        JSONObject json = new JSONObject();
        try {
            RuntimeChain runtime = blockchainRuntimeService.requireRuntime(chain);
            json.put("balance", chainJdbcRepository.sumLedgerTotalBalance(runtime.chain(), runtime.nativeSymbol()));

        } catch (Throwable e) {
            log.error("查询币种余额异常", e);
            return ResultUtils.failure("查询币种余额异常");
        }
        return ResultUtils.success(json);

    }

    @GetMapping("/balance/all")
    public ResponseResult<List<LedgerBalanceRecord>> genAllBalance() {
        try {
            return ResultUtils.success(chainJdbcRepository.listLedgerBalances());
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
            for (WalletPublicKey key : chainJdbcRepository.listEnabledWalletPublicKeys()) {
                addKeyInfo(keys, "KEY " + key.getKeySlot() + " (" + key.getKeyRole() + ")",
                        key.getPublicKey(), key.getRemark(), "m/44/1/0/0/0");
            }
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
            @RequestParam(value = "chain", defaultValue = "BTC") String chainParam) {
        Map<String, Object> result = new HashMap<>();
        try {
            RuntimeChain runtime = blockchainRuntimeService.requireRuntime(chainParam);

            // Default hot wallet address is fixed at userId=0, biz=0, index=0.
            String chain = runtime.chain();
            String symbol = runtime.nativeSymbol();
            chainJdbcRepository.findChainAddress(
                            chain,
                            symbol,
                            HotWalletRules.DEFAULT_HOT_USER_ID,
                            HotWalletRules.DEFAULT_HOT_BIZ,
                            HotWalletRules.DEFAULT_HOT_ADDRESS_INDEX,
                            HotWalletRules.DEFAULT_HOT_WALLET_ROLE)
                    .ifPresent(addr -> {
                        result.put("address", addr.getAddress());
                        result.put("childId", Math.toIntExact(addr.getAddressIndex()));
                        result.put("path", addr.getDerivationPath());
                        result.put("scriptType", "P2WSH");
                        result.put("witnessScript", "");
                        result.put("publicKeys", "");
                        result.put("currency", runtime.chain());
                    });

            // Balance
            result.put("balance", chainJdbcRepository.sumLedgerTotalBalance(chain, symbol));

            // Computed: derivation chain, witness script from 3 xpubs
            result.put("network", runtime.network());
            result.put("scriptType", "P2WSH 2-of-3 Multisig");
        } catch (Exception e) {
            log.error("getHotWalletInfo error chain={}", chainParam, e);
            return ResultUtils.failure("获取热钱包信息失败");
        }
        return ResultUtils.success(result);
    }

    /** GET /wallet/v1/addresses — paginated user addresses */
    @GetMapping("/addresses")
    public ResponseResult<List<AddressDto>> getAddresses(
            @RequestParam(value = "chain", defaultValue = "BTC") String chainParam,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "biz", required = false) Integer biz) {
        try {
            RuntimeChain runtime = blockchainRuntimeService.requireRuntime(chainParam);
            String chain = runtime.chain();
            String symbol = runtime.nativeSymbol();
            List<AddressDto> dtos = chainJdbcRepository.listChainAddresses(chain, symbol).stream()
                    .filter(record -> record.getUserId() != null && record.getUserId() > 0)
                    .filter(record -> userId == null || Objects.equals(record.getUserId(), userId))
                    .filter(record -> biz == null || Objects.equals(record.getBiz(), biz))
                    .sorted(Comparator.comparing(ChainAddressRecord::getId).reversed())
                    .limit(200)
                    .map(this::toAddressDto)
                    .collect(Collectors.toList());
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
            @RequestParam(value = "chain") String chainParam) {
        try {
            RuntimeChain runtime = blockchainRuntimeService.requireRuntime(chainParam);
            if (blockchainRuntimeService.isBitcoinLikeRuntime(runtime.chain())) {
                return ResultUtils.success(chainJdbcRepository.listUtxosByAddress(runtime.chain(), address, 50));
            }
            return ResultUtils.failure("仅支持BTC/LTC/DOGE/BCH UTXO交易查询");
        } catch (Exception e) {
            log.error("getAddressTransactions error address={}", address, e);
            return ResultUtils.failure("获取交易列表失败");
        }
    }

    private AddressDto toAddressDto(ChainAddressRecord record) {
        AddressDto dto = new AddressDto();
        dto.setUserId(record.getUserId());
        dto.setBiz(record.getBiz());
        dto.setAddress(record.getAddress());
        dto.setChildId(Math.toIntExact(record.getAddressIndex()));
        dto.setPath(record.getDerivationPath());
        dto.setNetwork("");
        dto.setScriptType("P2WSH");
        dto.setRedeemScript("");
        dto.setWitnessScript("");
        dto.setPublicKeys("");
        return dto;
    }
}
