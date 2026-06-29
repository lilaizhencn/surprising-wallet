package com.surprising.wallet.jobs.contractdeploy;

import com.surprising.commons.support.model.ResponseResult;
import com.surprising.commons.support.util.ResultUtils;
import com.surprising.wallet.jobs.walletapp.WalletAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/wallet/v1/app/contracts")
@CrossOrigin(
        origins = {"http://localhost:5173", "http://127.0.0.1:5173", "https://tokdou.com", "https://www.tokdou.com"},
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS}
)
public class ContractDeployController {
    private final WalletAuthService authService;
    private final ContractDeployService deployService;

    public ContractDeployController(WalletAuthService authService, ContractDeployService deployService) {
        this.authService = authService;
        this.deployService = deployService;
    }

    @GetMapping("/templates")
    public ResponseResult<Map<String, Object>> templates() {
        return ResultUtils.success(deployService.templates());
    }

    @PostMapping("/deployer-address")
    public ResponseResult<Map<String, Object>> deployerAddress(@RequestBody ContractDeployService.DeployerAddressRequest request,
                                                               HttpServletRequest servletRequest) {
        return ResultUtils.success(deployService.deployerAddress(authService.requireUser(servletRequest), request));
    }

    @PostMapping("/preview")
    public ResponseResult<Map<String, Object>> preview(@RequestBody ContractDeployService.ContractDeployRequest request,
                                                       HttpServletRequest servletRequest) {
        return ResultUtils.success(deployService.preview(authService.requireUser(servletRequest), request));
    }

    @PostMapping("/deploy")
    public ResponseResult<Map<String, Object>> deploy(@RequestBody ContractDeployService.ContractDeployRequest request,
                                                      HttpServletRequest servletRequest) {
        return ResultUtils.success(deployService.deploy(authService.requireUser(servletRequest), request));
    }

    @GetMapping("/orders")
    public ResponseResult<List<Map<String, Object>>> orders(
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            HttpServletRequest servletRequest) {
        return ResultUtils.success(deployService.orders(authService.requireUser(servletRequest), limit));
    }
}
