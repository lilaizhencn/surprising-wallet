package com.surprising.wallet.jobs.contractdeploy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractTemplateRegistryTest {
    @Test
    void loadsFixedErc20AndErc721Templates() {
        ContractTemplateRegistry registry = new ContractTemplateRegistry();

        ContractTemplateRegistry.CompiledTemplate erc20 = registry.require(
                ContractTemplateRegistry.TemplateType.ERC20);
        ContractTemplateRegistry.CompiledTemplate erc721 = registry.require(
                ContractTemplateRegistry.TemplateType.ERC721);

        assertEquals("TokDouERC20", erc20.contractName());
        assertEquals("TokDouERC721", erc721.contractName());
        assertTrue(erc20.source().contains("@openzeppelin/contracts/token/ERC20/ERC20.sol"));
        assertTrue(erc721.source().contains("@openzeppelin/contracts/token/ERC721/ERC721.sol"));
        assertFalse(erc20.bytecode().isBlank());
        assertFalse(erc721.bytecode().isBlank());
        assertFalse(erc20.abiJson().isBlank());
        assertFalse(erc721.abiJson().isBlank());
    }

    @Test
    void parsesOnlySupportedTemplateTypes() {
        assertEquals(ContractTemplateRegistry.TemplateType.ERC20,
                ContractTemplateRegistry.TemplateType.parse("erc20"));
        assertEquals(ContractTemplateRegistry.TemplateType.ERC721,
                ContractTemplateRegistry.TemplateType.parse("ERC721"));
        assertThrows(IllegalArgumentException.class,
                () -> ContractTemplateRegistry.TemplateType.parse("ERC1155"));
    }
}
