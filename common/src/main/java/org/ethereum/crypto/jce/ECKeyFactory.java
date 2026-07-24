/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.ethereum.crypto.jce;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;

/**
 * 椭圆曲线密钥工厂。
 *
 * <p>对 JCE {@link java.security.KeyFactory}（算法 "EC"）的薄封装，
 * 通过 Holder 模式缓存默认实例，同时支持按名称或 {@link java.security.Provider}
 * 获取工厂实例。</p>
 */
public final class ECKeyFactory {

    public static final String ALGORITHM = "EC";

    private static final String algorithmAssertionMsg =
            "Assumed the JRE supports EC key factories";

    private ECKeyFactory() {
    }

    private static class Holder {
        private static final KeyFactory INSTANCE;

        static {
            try {
                INSTANCE = KeyFactory.getInstance(ALGORITHM);
            } catch (NoSuchAlgorithmException ex) {
                throw new AssertionError(algorithmAssertionMsg, ex);
            }
        }
    }

    public static KeyFactory getInstance() {
        return Holder.INSTANCE;
    }

    public static KeyFactory getInstance(String provider) throws NoSuchProviderException {
        try {
            return KeyFactory.getInstance(ALGORITHM, provider);
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(algorithmAssertionMsg, ex);
        }
    }

    public static KeyFactory getInstance(Provider provider) {
        try {
            return KeyFactory.getInstance(ALGORITHM, provider);
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(algorithmAssertionMsg, ex);
        }
    }
}
