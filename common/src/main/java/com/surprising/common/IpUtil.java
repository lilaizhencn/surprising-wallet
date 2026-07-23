package com.surprising.common;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * @author lilaizhen
 */
@Slf4j
public class IpUtil {

    private IpUtil() {
        
    }

    /**
     * 获取请求主机IP地址,如果通过代理进来，则透过防火墙获取真实IP地址;
     */
    public static String getIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_CLUSTER_CLIENT_IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_FORWARDED_FOR");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_FORWARDED");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_VIA");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("REMOTE_ADDR");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (null != ip && !"".equals(ip.trim())
                && !"unknown".equalsIgnoreCase(ip)) {
            // get first ip from proxy ip
            int index = ip.indexOf(',');
            if (index != -1) {
                return ip.substring(0, index);
            } else {
                return ip;
            }
        }
        return ip;
    }

    public static Long ipToNumber(String ipaddr) {
        String[] ip = ipaddr.split("\\.");
        return 256 * 256 * 256 * Long.parseLong(ip[0]) +
                256 * 256 * Long.parseLong(ip[1]) +
                256 * Long.parseLong(ip[2]) +
                Long.parseLong(ip[3]);
    }

    public static String numberToIp(Long ipaddr) {
        long y = ipaddr % 256;
        long m = (ipaddr - y) / (256 * 256 * 256);
        long n = (ipaddr - 256 * 256 * 256 * m - y) / (256 * 256);
        long x = (ipaddr - 256 * 256 * 256 * m - 256 * 256 * n - y) / 256;
        return m + "." + n + "." + x + "." + y;
    }

    /**
     * 本机IP 列表
     */
    public static List<String> getLocalIps() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
            while (enumeration.hasMoreElements()) {
                NetworkInterface iface = enumeration.nextElement();
                // filters out 127.0.0.1 and inactive interfaces
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> inetAddresses = iface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    String ip = inetAddresses.nextElement().getHostAddress();
                    // 排除 回环IP/ipv6 地址
                    if (ip.contains(":")) {
                        continue;
                    }
                    if (StringUtils.isNotBlank(ip)) {
                        ips.add(ip);
                    }
                }
            }
        } catch (SocketException e) {
            log.error("获取内网地址错误", e);
        }
        return ips;
    }

    /**
     * 获取内网IP
     */
    public static String getLocalIntranetIp() {
        List<String> ips = getLocalIps();
        return ips.stream().filter(IpUtil::isIntranetIp).findFirst().orElse("");
    }

    /**
     * 判断是否为内网IP
     * tcp/ip协议中, 专门保留了三个IP地址区域作为私有地址, 其地址范围如下:
     * 10.0.0.0/8: 10.0.0.0～10.255.255.255
     * 172.16.0.0/12: 172.16.0.0～172.31.255.255
     * 192.168.0.0/16: 192.168.0.0～192.168.255.255
     */
    public static boolean isIntranetIp(String ip) {
        try {
            if (ip.startsWith("10.") || ip.startsWith("192.168.")) {
                return true;
            }
            // 172.16.x.x～172.31.x.x
            String[] ns = ip.split("\\.");
            int ipSub = Integer.parseInt(ns[0] + ns[1]);
            if (ipSub >= 17216 && ipSub <= 17231) {
                return true;
            }
        } catch (Exception e) {
            log.error("判断是否为内网IP 错误", e);
        }
        return false;
    }
}
