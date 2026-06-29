// SPDX-License-Identifier: MIT
pragma solidity ^0.8.19;

import "@openzeppelin/contracts4/access/Ownable.sol";
import "@openzeppelin/contracts4/token/ERC721/ERC721.sol";
import "@openzeppelin/contracts4/token/ERC721/extensions/ERC721Burnable.sol";
import "@openzeppelin/contracts4/token/ERC721/extensions/ERC721Enumerable.sol";
import "@openzeppelin/contracts4/token/ERC721/extensions/ERC721Pausable.sol";
import "@openzeppelin/contracts4/token/ERC721/extensions/ERC721URIStorage.sol";

contract TokDouTRC721 is ERC721, ERC721Enumerable, ERC721URIStorage, ERC721Pausable, ERC721Burnable, Ownable {
    uint256 private nextTokenId;
    uint256 public immutable maxSupply;
    string private baseTokenUri;

    constructor(
        string memory collectionName,
        string memory collectionSymbol,
        string memory baseUri,
        uint256 collectionMaxSupply,
        address initialOwner
    )
        ERC721(collectionName, collectionSymbol)
    {
        require(initialOwner != address(0), "owner is zero");
        require(collectionMaxSupply > 0, "max supply is zero");
        maxSupply = collectionMaxSupply;
        baseTokenUri = baseUri;
        _transferOwnership(initialOwner);
    }

    function safeMint(address to, string memory uri) external onlyOwner returns (uint256) {
        require(totalSupply() < maxSupply, "max supply reached");
        uint256 tokenId = ++nextTokenId;
        _safeMint(to, tokenId);
        if (bytes(uri).length > 0) {
            _setTokenURI(tokenId, uri);
        }
        return tokenId;
    }

    function setBaseURI(string memory baseUri) external onlyOwner {
        baseTokenUri = baseUri;
    }

    function pause() external onlyOwner {
        _pause();
    }

    function unpause() external onlyOwner {
        _unpause();
    }

    function _baseURI() internal view override returns (string memory) {
        return baseTokenUri;
    }

    function _beforeTokenTransfer(address from, address to, uint256 tokenId, uint256 batchSize)
        internal
        override(ERC721, ERC721Enumerable, ERC721Pausable)
    {
        super._beforeTokenTransfer(from, to, tokenId, batchSize);
    }

    function _burn(uint256 tokenId) internal override(ERC721, ERC721URIStorage) {
        super._burn(tokenId);
    }

    function tokenURI(uint256 tokenId)
        public
        view
        override(ERC721, ERC721URIStorage)
        returns (string memory)
    {
        return super.tokenURI(tokenId);
    }

    function supportsInterface(bytes4 interfaceId)
        public
        view
        override(ERC721, ERC721Enumerable, ERC721URIStorage)
        returns (bool)
    {
        return super.supportsInterface(interfaceId);
    }
}
