/*
 * java-tron is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-tron is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.tron.wallet.constants;

/**
 * TRON网络常量定义类，定义了TRON地址生成所需的前缀字节和地址长度等核心常量。
 *
 * <h3>TRON地址格式</h3>
 * TRON地址分为主网地址和测试网地址两种格式，均为Base58Check编码：
 * <ul>
 *   <li><b>主网地址</b>：以{@code T}开头，地址前缀字节为{@link #ADD_PRE_FIX_BYTE_MAINNET}（{@code 0x41}）</li>
 *   <li><b>测试网地址</b>：以{@code 27}开头（Base58编码后），地址前缀字节为{@link #ADD_PRE_FIX_BYTE_TESTNET}（{@code 0xa0}）</li>
 * </ul>
 *
 * <h3>地址计算流程</h3>
 * <ol>
 *   <li>从secp256k1公钥（64字节未压缩）计算Keccak-256哈希</li>
 *   <li>取哈希的后20字节作为地址体</li>
 *   <li>将地址体首字节替换为网络前缀字节（主网0x41 / 测试网0xa0）</li>
 *   <li>Base58Check编码（Base58 + SHA-256双重校验和）</li>
 * </ol>
 *
 * <p>最终地址字符串长度为{@link #ADDRESS_SIZE}（42字符，含前缀和校验位）。</p>
 */
public class Constant {
    //41 + address
    public static final byte ADD_PRE_FIX_BYTE_MAINNET = (byte) 0x41;
    public static final String ADD_PRE_FIX_STRING_MAINNET = "41";
    //a0 + address
    public static final byte ADD_PRE_FIX_BYTE_TESTNET = (byte) 0xa0;
    public static final String ADD_PRE_FIX_STRING_TESTNET = "a0";
    public static final int ADDRESS_SIZE = 42;

}
