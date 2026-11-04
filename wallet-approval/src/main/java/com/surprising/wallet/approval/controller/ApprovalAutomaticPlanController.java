package com.surprising.wallet.approval.controller;

import com.surprising.wallet.approval.filter.ApprovalAuthenticationFilter;
import com.surprising.wallet.approval.service.ApprovalAutomationService;
import com.surprising.wallet.common.approval.AutomaticParentPlan;
import com.surprising.wallet.common.approval.AutomaticOperation;
import com.surprising.wallet.common.approval.CollectionDestinationPolicy;
import com.surprising.wallet.common.approval.HotWalletInventoryObservation;
import com.surprising.wallet.common.approval.HotWalletIncidentInventoryObservation;
import com.surprising.wallet.common.approval.HotWalletSettledSpendRequest;
import com.surprising.wallet.common.approval.HotWalletSettledSpendObservation;
import com.surprising.wallet.common.approval.HotWalletSettledCollectionRequest;
import com.surprising.wallet.common.approval.HotWalletSettledCollectionObservation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/approval/service/v1/automatic-plans")
public class ApprovalAutomaticPlanController {
    private final ApprovalAutomationService automation;
    public ApprovalAutomaticPlanController(ApprovalAutomationService automation){this.automation=automation;}
    @PostMapping public AutomaticOperation plan(@RequestBody AutomaticParentPlan plan,HttpServletRequest request) {
        if(!(request.getAttribute(ApprovalAuthenticationFilter.CLIENT) instanceof ApprovalAuthenticationFilter.ServiceClient client))throw new SecurityException("independent service mTLS required");
        client.requireTenant(plan.tenantId());return automation.plan(plan,client.identity());
    }
    @GetMapping("/destinations") public CollectionDestinationPolicy destination(@RequestParam UUID tenantId,
            @RequestParam String chain,@RequestParam String network,@RequestParam String assetSymbol,
            @RequestParam UUID destinationKeyId,HttpServletRequest request) {
        if(!(request.getAttribute(ApprovalAuthenticationFilter.CLIENT) instanceof ApprovalAuthenticationFilter.ServiceClient client))
            throw new SecurityException("independent service mTLS required");
        client.requireTenant(tenantId);
        return automation.collectionDestination(tenantId,chain,network,assetSymbol,destinationKeyId);
    }
    @GetMapping("/inventory") public HotWalletInventoryObservation inventory(@RequestParam UUID tenantId,
            @RequestParam String chain,@RequestParam String network,@RequestParam String assetSymbol,
            @RequestParam UUID destinationKeyId,HttpServletRequest request) {
        if(!(request.getAttribute(ApprovalAuthenticationFilter.CLIENT) instanceof ApprovalAuthenticationFilter.ServiceClient client))
            throw new SecurityException("independent service mTLS required");
        client.requireTenant(tenantId);
        return automation.hotInventory(tenantId,chain,network,assetSymbol,destinationKeyId);
    }
    @GetMapping("/incident-inventory") public HotWalletIncidentInventoryObservation incidentInventory(
            @RequestParam UUID tenantId,@RequestParam String chain,@RequestParam String network,
            @RequestParam String assetSymbol,@RequestParam UUID keyId,HttpServletRequest request) {
        if(!(request.getAttribute(ApprovalAuthenticationFilter.CLIENT) instanceof ApprovalAuthenticationFilter.ServiceClient client))
            throw new SecurityException("independent service mTLS required");
        client.requireTenant(tenantId);
        return automation.incidentInventory(tenantId,chain,network,assetSymbol,keyId);
    }
    @PostMapping("/settled-direct-spend") public HotWalletSettledSpendObservation settledDirectSpend(
            @RequestBody HotWalletSettledSpendRequest spend,HttpServletRequest request) {
        if(!(request.getAttribute(ApprovalAuthenticationFilter.CLIENT) instanceof ApprovalAuthenticationFilter.ServiceClient client))
            throw new SecurityException("independent service mTLS required");
        if(spend==null || spend.tenantId()==null)throw new IllegalArgumentException("owned settled spend request required");
        client.requireTenant(spend.tenantId());
        return automation.settledDirectSpend(spend);
    }
    @PostMapping("/settled-direct-collection") public HotWalletSettledCollectionObservation settledDirectCollection(
            @RequestBody HotWalletSettledCollectionRequest collection,HttpServletRequest request) {
        if(!(request.getAttribute(ApprovalAuthenticationFilter.CLIENT) instanceof ApprovalAuthenticationFilter.ServiceClient client))
            throw new SecurityException("independent service mTLS required");
        if(collection==null || collection.tenantId()==null)
            throw new IllegalArgumentException("owned settled collection request required");
        client.requireTenant(collection.tenantId());
        return automation.settledDirectCollection(collection);
    }
}
