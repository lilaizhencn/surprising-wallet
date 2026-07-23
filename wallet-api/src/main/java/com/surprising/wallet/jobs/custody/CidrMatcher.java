package com.surprising.wallet.jobs.custody;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class CidrMatcher {
    private CidrMatcher() {
    }

    public static boolean matches(String cidr, String address) {
        if (cidr == null || cidr.isBlank() || address == null || address.isBlank()) {
            return false;
        }
        try {
            String[] parts = cidr.trim().split("/", 2);
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            byte[] candidate = InetAddress.getByName(address.trim()).getAddress();
            if (network.length != candidate.length) {
                return false;
            }
            int maxBits = network.length * 8;
            int prefix = parts.length == 1 ? maxBits : Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > maxBits) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (network[i] != candidate[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (8 - remainingBits);
            return (network[fullBytes] & mask) == (candidate[fullBytes] & mask);
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
    }
}
