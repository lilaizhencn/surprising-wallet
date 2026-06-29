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
                ContractTemplateRegistry.ContractFamily.EVM,
                ContractTemplateRegistry.TemplateType.ERC20);
        ContractTemplateRegistry.CompiledTemplate erc721 = registry.require(
                ContractTemplateRegistry.ContractFamily.EVM,
                ContractTemplateRegistry.TemplateType.ERC721);
        ContractTemplateRegistry.CompiledTemplate trc20 = registry.require(
                ContractTemplateRegistry.ContractFamily.TRON,
                ContractTemplateRegistry.TemplateType.ERC20);
        ContractTemplateRegistry.CompiledTemplate trc721 = registry.require(
                ContractTemplateRegistry.ContractFamily.TRON,
                ContractTemplateRegistry.TemplateType.ERC721);
        ContractTemplateRegistry.CompiledTemplate nep141 = registry.require(
                ContractTemplateRegistry.ContractFamily.NEAR,
                ContractTemplateRegistry.TemplateType.ERC20);
        ContractTemplateRegistry.CompiledTemplate nep171 = registry.require(
                ContractTemplateRegistry.ContractFamily.NEAR,
                ContractTemplateRegistry.TemplateType.ERC721);
        ContractTemplateRegistry.CompiledTemplate splToken = registry.require(
                ContractTemplateRegistry.ContractFamily.SOLANA,
                ContractTemplateRegistry.TemplateType.ERC20);
        ContractTemplateRegistry.CompiledTemplate splNft = registry.require(
                ContractTemplateRegistry.ContractFamily.SOLANA,
                ContractTemplateRegistry.TemplateType.ERC721);
        ContractTemplateRegistry.CompiledTemplate aptosCoin = registry.require(
                ContractTemplateRegistry.ContractFamily.APTOS,
                ContractTemplateRegistry.TemplateType.ERC20);
        ContractTemplateRegistry.CompiledTemplate aptosNft = registry.require(
                ContractTemplateRegistry.ContractFamily.APTOS,
                ContractTemplateRegistry.TemplateType.ERC721);
        ContractTemplateRegistry.CompiledTemplate suiCoin = registry.require(
                ContractTemplateRegistry.ContractFamily.SUI,
                ContractTemplateRegistry.TemplateType.ERC20);
        ContractTemplateRegistry.CompiledTemplate suiNft = registry.require(
                ContractTemplateRegistry.ContractFamily.SUI,
                ContractTemplateRegistry.TemplateType.ERC721);

        assertEquals("TokDouERC20", erc20.contractName());
        assertEquals("TokDouERC721", erc721.contractName());
        assertEquals("TokDouTRC20", trc20.contractName());
        assertEquals("TokDouTRC721", trc721.contractName());
        assertEquals("TokDouNep141", nep141.contractName());
        assertEquals("TokDouNep171", nep171.contractName());
        assertEquals("TokDouSplToken", splToken.contractName());
        assertEquals("TokDouSplNft", splNft.contractName());
        assertEquals("TokDouAptosCoin", aptosCoin.contractName());
        assertEquals("TokDouAptosNft", aptosNft.contractName());
        assertEquals("TokDouSuiCoin", suiCoin.contractName());
        assertEquals("TokDouSuiNft", suiNft.contractName());
        assertTrue(erc20.source().contains("@openzeppelin/contracts/token/ERC20/ERC20.sol"));
        assertTrue(erc721.source().contains("@openzeppelin/contracts/token/ERC721/ERC721.sol"));
        assertTrue(trc20.source().contains("@openzeppelin/contracts4/token/ERC20/ERC20.sol"));
        assertTrue(trc721.source().contains("@openzeppelin/contracts4/token/ERC721/ERC721.sol"));
        assertTrue(nep141.source().contains("FungibleTokenCore"));
        assertTrue(nep171.source().contains("NonFungibleTokenCore"));
        assertTrue(splToken.source().contains("SPL Token mint"));
        assertTrue(splNft.source().contains("single-supply Solana SPL mint"));
        assertTrue(aptosCoin.source().contains("managed_coin"));
        assertTrue(aptosNft.source().contains("managed_coin"));
        assertTrue(suiCoin.source().contains("MintAuthority"));
        assertTrue(suiNft.source().contains("public struct Collection"));
        assertFalse(erc20.bytecode().isBlank());
        assertFalse(erc721.bytecode().isBlank());
        assertFalse(trc20.bytecode().isBlank());
        assertFalse(trc721.bytecode().isBlank());
        assertTrue(nep141.binary().length > 0);
        assertTrue(nep171.binary().length > 0);
        assertTrue(splToken.bytecode().isBlank());
        assertTrue(splNft.bytecode().isBlank());
        assertTrue(aptosCoin.bytecode().isBlank());
        assertTrue(aptosNft.bytecode().isBlank());
        assertTrue(suiCoin.bytecode().isBlank());
        assertTrue(suiNft.bytecode().isBlank());
        assertFalse(erc20.abiJson().isBlank());
        assertFalse(erc721.abiJson().isBlank());
        assertFalse(trc20.abiJson().isBlank());
        assertFalse(trc721.abiJson().isBlank());
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
