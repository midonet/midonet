/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.openstack.metadata

import org.midonet.packets.IPv4Addr

/*
 * NOTE(yamamoto):
 *
 * ODP port number is 16-bit.
 *
 * There are special values we can exclude:
 *   ODPP_LOCAL    0
 *   tunnel ports  1-3
 *   ODPP_NONE     65535
 *
 * There are special IP addresses we need to exclude:
 *   169.254.0.0
 *   169.254.169.254
 *   169.254.255.255
 *
 * Our static mapping between ODP port number and IP address:
 *
 *   N     <-> 169.254.0.0 + N - 3  (where N <= 43520)
 *   N     <-> 169.254.0.0 + N - 2  (where N >  43520)
 *
 *   4     <-> 169.254.0.1
 *          :
 *   43520 <-> 169.254.169.253
 *   43521 <-> 169.254.169.255
 *          :
 *   65534 <-> 169.254.255.252
 */

object AddressManager {
    def getRemoteAddress(dpPortNo: Int) = {
        val x =
            if (dpPortNo <= 43520)
                dpPortNo - 3
            else
                dpPortNo - 2
        val hi = (x >> 8) & 0xff
        val lo = x & 0xff
        s"169.254.${hi}.${lo}"
    }

    def putRemoteAddress(addr: String) = {
        val n = IPv4Addr.stringToInt(addr)
        val hi = (n >> 8) & 0xff
        val lo = n & 0xff
        val x = (hi << 8) + lo
        val dpPortNo =
            if (x + 3 <= 43520)
                x + 3
            else
                x + 2
        dpPortNo
    }
}
