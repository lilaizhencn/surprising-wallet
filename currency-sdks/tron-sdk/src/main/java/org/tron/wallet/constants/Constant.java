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

public class Constant {
    //41 + address
    public static final byte ADD_PRE_FIX_BYTE_MAINNET = (byte) 0x41;
    public static final String ADD_PRE_FIX_STRING_MAINNET = "41";
    //a0 + address
    public static final byte ADD_PRE_FIX_BYTE_TESTNET = (byte) 0xa0;
    public static final String ADD_PRE_FIX_STRING_TESTNET = "a0";
    public static final int ADDRESS_SIZE = 42;

}
