package com.surprising.wallet.jobs.walletapp;

import com.surprising.commons.support.model.ResponseResult;
import com.surprising.commons.support.util.ResultUtils;
import com.surprising.wallet.jobs.walletapp.WalletAuthService.AuthRequest;
import com.surprising.wallet.jobs.walletapp.WalletAuthService.WalletUser;
import com.surprising.wallet.jobs.walletapp.WalletAppService.DepositAddressRequest;
import com.surprising.wallet.jobs.walletapp.WalletAppService.TransferRequest;
import com.surprising.wallet.jobs.walletapp.WalletAppService.WithdrawRequest;
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
@RequestMapping("/wallet/v1")
@CrossOrigin(
        origins = {"http://localhost:5173", "http://127.0.0.1:5173", "https://tokdou.com", "https://www.tokdou.com"},
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS}
)
public class WalletAppController {
    private final WalletAuthService authService;
    private final WalletAppService appService;

    public WalletAppController(WalletAuthService authService, WalletAppService appService) {
        this.authService = authService;
        this.appService = appService;
    }

    @PostMapping("/auth/register")
    public ResponseResult<WalletAuthService.AuthPayload> register(@RequestBody AuthRequest request,
                                                                  HttpServletRequest servletRequest) {
        return ResultUtils.success(authService.register(request, servletRequest));
    }

    @PostMapping("/auth/login")
    public ResponseResult<WalletAuthService.AuthPayload> login(@RequestBody AuthRequest request,
                                                               HttpServletRequest servletRequest) {
        return ResultUtils.success(authService.login(request, servletRequest));
    }

    @GetMapping("/auth/me")
    public ResponseResult<WalletUser> me(HttpServletRequest servletRequest) {
        return ResultUtils.success(authService.requireUser(servletRequest));
    }

    @PostMapping("/auth/logout")
    public ResponseResult<Map<String, Object>> logout(HttpServletRequest servletRequest) {
        authService.logout(servletRequest);
        return ResultUtils.success(Map.of("ok", true));
    }

    @GetMapping("/app/assets")
    public ResponseResult<Map<String, Object>> assets() {
        return ResultUtils.success(appService.assetCatalog());
    }

    @GetMapping("/app/portfolio")
    public ResponseResult<Map<String, Object>> portfolio(
            @RequestParam(value = "hideZero", defaultValue = "false") boolean hideZero,
            HttpServletRequest servletRequest) {
        return ResultUtils.success(appService.portfolio(authService.requireUser(servletRequest), hideZero));
    }

    @GetMapping("/app/orders")
    public ResponseResult<List<Map<String, Object>>> orders(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            HttpServletRequest servletRequest) {
        return ResultUtils.success(appService.orders(authService.requireUser(servletRequest), limit));
    }

    @GetMapping("/app/deposit-address")
    public ResponseResult<Map<String, Object>> depositAddress(
            @RequestParam("chain") String chain,
            @RequestParam("symbol") String symbol,
            HttpServletRequest servletRequest) {
        return ResultUtils.success(appService.depositAddress(
                authService.requireUser(servletRequest), new DepositAddressRequest(chain, symbol), false));
    }

    @PostMapping("/app/deposit-address")
    public ResponseResult<Map<String, Object>> newDepositAddress(@RequestBody DepositAddressRequest request,
                                                                 HttpServletRequest servletRequest) {
        return ResultUtils.success(appService.depositAddress(authService.requireUser(servletRequest), request, true));
    }

    @PostMapping("/app/withdraw")
    public ResponseResult<Map<String, Object>> withdraw(@RequestBody WithdrawRequest request,
                                                        HttpServletRequest servletRequest) {
        return ResultUtils.success(appService.withdraw(authService.requireUser(servletRequest), request));
    }

    @PostMapping("/app/transfer")
    public ResponseResult<Map<String, Object>> transfer(@RequestBody TransferRequest request,
                                                        HttpServletRequest servletRequest) {
        return ResultUtils.success(appService.transfer(authService.requireUser(servletRequest), request));
    }

    @PostMapping("/app/test-faucet/doge")
    public ResponseResult<Map<String, Object>> dogeRegtestFaucet(HttpServletRequest servletRequest) {
        return ResultUtils.success(appService.dogeRegtestFaucet(authService.requireUser(servletRequest)));
    }

    @PostMapping("/app/test-faucet/xmr")
    public ResponseResult<Map<String, Object>> xmrRegtestFaucet(HttpServletRequest servletRequest) {
        return ResultUtils.success(appService.xmrRegtestFaucet(authService.requireUser(servletRequest)));
    }
}
