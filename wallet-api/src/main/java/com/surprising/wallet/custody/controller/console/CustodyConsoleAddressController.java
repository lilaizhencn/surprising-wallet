package com.surprising.wallet.custody.controller.console;

import com.surprising.wallet.custody.service.CustodyAddressService.AddressView;
import com.surprising.wallet.custody.service.CustodyAddressService.CreateAddressCommand;
import com.surprising.wallet.custody.service.CustodyAddressService.UpdateAddressCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.surprising.wallet.custody.service.CustodyAddressService;
import com.surprising.wallet.custody.model.CustodyRequestSupport;

@RestController
@RequestMapping("/custody/console/v1")
public class CustodyConsoleAddressController {
    /** 地址服务，处理创建、更新、列表逻辑。 */
    private final CustodyAddressService addresses;

    /**
     * 注入地址服务。
     */
    public CustodyConsoleAddressController(CustodyAddressService addresses) {
        this.addresses = addresses;
    }

    /**
     * 查询地址列表，支持链、来源、状态、关键字过滤和分页。
     */
    @GetMapping("/addresses")
    public List<AddressView> addresses(
            @RequestParam(defaultValue = "") String chain,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return addresses.list(CustodyRequestSupport.requirePrincipal(request),
                chain, source, status, search, limit, offset);
    }

    /**
     * 创建控制台地址，记录来源为控制台。
     */
    @PostMapping("/addresses")
    public AddressView create(@RequestBody CreateAddressCommand body, HttpServletRequest request) {
        return addresses.create(
                CustodyRequestSupport.requirePrincipal(request),
                body,
                "CONSOLE",
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * 更新地址属性（状态、备注等），支持部分字段更新。
     */
    @PatchMapping("/addresses/{addressId}")
    public AddressView update(@PathVariable UUID addressId,
                              @RequestBody UpdateAddressCommand body,
                              HttpServletRequest request) {
        return addresses.update(
                CustodyRequestSupport.requirePrincipal(request),
                addressId,
                body,
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * 查询资产维度清单，供后台侧余额与链路配置展示。
     */
    @GetMapping("/assets")
    public List<Map<String, Object>> assets(HttpServletRequest request) {
        return addresses.assets(CustodyRequestSupport.requirePrincipal(request));
    }
}
