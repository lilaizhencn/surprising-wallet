package com.surprising.wallet.controller;

import com.surprising.wallet.model.CustodyRequestSupport;
import com.surprising.wallet.service.CustodyHotWalletService;
import com.surprising.wallet.service.HotWalletInventoryService;
import com.surprising.wallet.service.HotWalletSettledSpendReconciliationService;
import com.surprising.wallet.service.HotWalletAggregateExposureService;
import com.surprising.wallet.service.HotWalletSettledCollectionReconciliationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/custody/console/v1/hot-wallets")
public class CustodyConsoleHotWalletController {
    private final CustodyHotWalletService wallets;
    private final HotWalletInventoryService inventory;
    private final HotWalletSettledSpendReconciliationService reconciliations;
    private final HotWalletAggregateExposureService aggregate;
    private final HotWalletSettledCollectionReconciliationService collectionReconciliations;
    public CustodyConsoleHotWalletController(CustodyHotWalletService wallets,HotWalletInventoryService inventory,
            HotWalletSettledSpendReconciliationService reconciliations,HotWalletAggregateExposureService aggregate,
            HotWalletSettledCollectionReconciliationService collectionReconciliations) {
        this.wallets = wallets;this.inventory=inventory;this.reconciliations=reconciliations;
        this.aggregate=aggregate;this.collectionReconciliations=collectionReconciliations;
    }

    @GetMapping("/exposure-limits")
    public List<com.surprising.wallet.repository.CustodyHotWalletExposureLimitRepository.Limit> exposureLimits(
            @RequestParam String chain,HttpServletRequest request) {
        return aggregate.list(CustodyRequestSupport.requirePrincipal(request),chain);
    }

    @PutMapping("/exposure-limits/{chain}/{asset}")
    public com.surprising.wallet.repository.CustodyHotWalletExposureLimitRepository.Limit setExposureLimit(
            @PathVariable String chain,@PathVariable String asset,
            @RequestBody HotWalletAggregateExposureService.SetLimitCommand command,HttpServletRequest request) {
        return aggregate.set(CustodyRequestSupport.requirePrincipal(request),chain,asset,command,
                CustodyRequestSupport.clientIp(request));
    }

    @GetMapping
    public List<CustodyHotWalletService.WalletView> list(@RequestParam String chain, HttpServletRequest request) {
        return wallets.list(CustodyRequestSupport.requirePrincipal(request), chain);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustodyHotWalletService.WalletView create(@RequestBody CustodyHotWalletService.CreateCommand command,
                                                      HttpServletRequest request) {
        return wallets.createStandby(CustodyRequestSupport.requirePrincipal(request), command,
                CustodyRequestSupport.clientIp(request));
    }

    @PutMapping("/{walletId}/policies/{asset}")
    public CustodyHotWalletService.WalletView setPolicy(@PathVariable UUID walletId,
            @PathVariable String asset, @RequestBody CustodyHotWalletService.PolicyCommand command,
            HttpServletRequest request) {
        return wallets.setPolicy(CustodyRequestSupport.requirePrincipal(request), walletId, asset, command,
                CustodyRequestSupport.clientIp(request));
    }

    @PostMapping("/{walletId}/quarantine")
    public CustodyHotWalletService.WalletView quarantine(@PathVariable UUID walletId,
            @RequestBody QuarantineCommand command, HttpServletRequest request) {
        return wallets.quarantine(CustodyRequestSupport.requirePrincipal(request), walletId,
                command.reason(), CustodyRequestSupport.clientIp(request));
    }

    @PostMapping("/{walletId}/collection-rule-preflight")
    public CustodyHotWalletService.CollectionRulePreflight collectionRulePreflight(@PathVariable UUID walletId,
            HttpServletRequest request) {
        return wallets.collectionRulePreflight(CustodyRequestSupport.requirePrincipal(request), walletId);
    }

    @PostMapping("/{walletId}/activate")
    public CustodyHotWalletService.WalletView activate(@PathVariable UUID walletId,
            @RequestBody CustodyHotWalletService.ActivateCommand command, HttpServletRequest request) {
        return wallets.activate(CustodyRequestSupport.requirePrincipal(request),walletId,command,
                CustodyRequestSupport.clientIp(request));
    }

    @PostMapping("/emergency-switch")
    public CustodyHotWalletService.EmergencySwitchView emergencySwitch(
            @RequestBody CustodyHotWalletService.EmergencySwitchCommand command,
            HttpServletRequest request) {
        return wallets.emergencySwitch(CustodyRequestSupport.requirePrincipal(request),command,
                CustodyRequestSupport.clientIp(request));
    }

    @GetMapping("/{walletId}/incident-inventory")
    public CustodyHotWalletService.IncidentInventoryView incidentInventory(@PathVariable UUID walletId,
            HttpServletRequest request) {
        return wallets.incidentInventory(CustodyRequestSupport.requirePrincipal(request),walletId,
                CustodyRequestSupport.clientIp(request));
    }

    @PostMapping("/{walletId}/inventory/{asset}/refresh")
    public HotWalletInventoryService.InventoryView refreshInventory(@PathVariable UUID walletId,
            @PathVariable String asset,HttpServletRequest request) {
        return inventory.refresh(CustodyRequestSupport.requirePrincipal(request),walletId,asset,
                CustodyRequestSupport.clientIp(request));
    }

    @PostMapping("/{walletId}/confirmed-spends/{orderNo}/reconcile")
    public HotWalletSettledSpendReconciliationService.ReconciliationView reconcileConfirmedSpend(
            @PathVariable UUID walletId,@PathVariable String orderNo,HttpServletRequest request) {
        return reconciliations.reconcile(CustodyRequestSupport.requirePrincipal(request),walletId,orderNo,
                CustodyRequestSupport.clientIp(request));
    }

    @PostMapping("/{walletId}/confirmed-collections/{collectionNo}/reconcile")
    public HotWalletSettledCollectionReconciliationService.ReconciliationView reconcileConfirmedCollection(
            @PathVariable UUID walletId,@PathVariable String collectionNo,HttpServletRequest request) {
        return collectionReconciliations.reconcile(CustodyRequestSupport.requirePrincipal(request),
                walletId,collectionNo,CustodyRequestSupport.clientIp(request));
    }

    public record QuarantineCommand(String reason) { }
}
