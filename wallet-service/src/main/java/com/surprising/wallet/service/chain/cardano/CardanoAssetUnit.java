package com.surprising.wallet.service.chain.cardano;

import java.util.Locale;
final class CardanoAssetUnit {
    static final String LOVELACE = "lovelace";
    private CardanoAssetUnit() {
    }
    static String normalize(String value) {
        String unit = value == null ? "" : value.trim();
        if (unit.equalsIgnoreCase(LOVELACE)) {
            return LOVELACE;
        }
        if (unit.contains(".")) {
            String[] parts = unit.split("\\.", 2);
            return hex(parts[0], "policy id") + hex(parts.length > 1 ? parts[1] : "", "asset name");
        }
        return hex(unit, "asset unit");
    }
    static String fromTokenContract(String contractAddress) {
        String unit = normalize(contractAddress);
        if (LOVELACE.equals(unit) || unit.length() < 56) {
            throw new IllegalArgumentException("Cardano token contract_address must be policyId.assetNameHex");
        }
        return unit;
    }
    static String policyId(String unit) {
        String normalized = normalize(unit);
        if (normalized.length() < 56) {
            throw new IllegalArgumentException("Cardano asset unit is missing policy id");
        }
        return normalized.substring(0, 56);
    }
    static String assetNameHex(String unit) {
        String normalized = normalize(unit);
        if (normalized.length() <= 56) {
            return "";
        }
        return normalized.substring(56);
    }
    static long depositLogIndex(int outputIndex, int assetIndex) {
        return outputIndex * 10_000L + assetIndex;
    }
    private static String hex(String value, String label) {
        String hex = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (hex.startsWith("0x")) {
            hex = hex.substring(2);
        }
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Cardano " + label + " hex length must be even");
        }
        if (!hex.matches("[0-9a-f]*")) {
            throw new IllegalArgumentException("Cardano " + label + " must be hex");
        }
        return hex;
    }
}
