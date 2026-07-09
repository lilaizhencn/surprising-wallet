// SPDX-License-Identifier: MIT
pragma solidity ^0.8.19;

import "@openzeppelin/contracts4/access/Ownable.sol";
import "@openzeppelin/contracts4/token/ERC20/ERC20.sol";
import "@openzeppelin/contracts4/token/ERC20/extensions/ERC20Burnable.sol";
import "@openzeppelin/contracts4/token/ERC20/extensions/ERC20Capped.sol";
import "@openzeppelin/contracts4/token/ERC20/extensions/ERC20Pausable.sol";
import "@openzeppelin/contracts4/token/ERC20/extensions/draft-ERC20Permit.sol";

contract TokDouTRC20 is ERC20, ERC20Burnable, ERC20Capped, ERC20Pausable, ERC20Permit, Ownable {
    uint8 private immutable customDecimals;
    bool public immutable mintingEnabled;

    constructor(
        string memory tokenName,
        string memory tokenSymbol,
        uint8 tokenDecimals,
        uint256 initialSupply,
        uint256 maxSupply,
        address initialOwner,
        bool allowOwnerMint
    )
        ERC20(tokenName, tokenSymbol)
        ERC20Capped(maxSupply)
        ERC20Permit(tokenName)
    {
        require(initialOwner != address(0), "owner is zero");
        require(tokenDecimals <= 18, "decimals too high");
        require(maxSupply > 0, "max supply is zero");
        require(initialSupply <= maxSupply, "initial supply exceeds cap");

        customDecimals = tokenDecimals;
        mintingEnabled = allowOwnerMint;
        _transferOwnership(initialOwner);

        if (initialSupply > 0) {
            _mint(initialOwner, initialSupply);
        }
    }

    function decimals() public view override returns (uint8) {
        return customDecimals;
    }

    function mint(address to, uint256 amount) external onlyOwner {
        require(mintingEnabled, "minting disabled");
        _mint(to, amount);
    }

    function pause() external onlyOwner {
        _pause();
    }

    function unpause() external onlyOwner {
        _unpause();
    }

    function _mint(address account, uint256 amount) internal override(ERC20, ERC20Capped) {
        super._mint(account, amount);
    }

    function _beforeTokenTransfer(address from, address to, uint256 amount)
        internal
        override(ERC20, ERC20Pausable)
    {
        super._beforeTokenTransfer(from, to, amount);
    }
}
