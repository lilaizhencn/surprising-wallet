package com.surprising.wallet.custody.model;

import java.net.InetAddress;
import java.net.UnknownHostException;
/**
 * CIDR（无类别域间路由）IP 网段匹配工具。
 *
 * <p>用于校验请求来源 IP 是否在租户配置的 IP 白名单范围内。
 * 支持 IPv4 和 IPv6，支持子网前缀长度（如 192.168.1.0/24）。
 */
public final class CidrMatcher {
    private CidrMatcher() {    }
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
