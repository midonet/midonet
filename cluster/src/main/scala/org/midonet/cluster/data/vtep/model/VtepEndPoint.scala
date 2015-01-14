/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.cluster.data.vtep.model

import org.midonet.packets.IPv4Addr

/**
 * VTEP end point connection information
 */
case class VtepEndPoint(mgmtIp: IPv4Addr, mgmtPort: Int) {
    private val str: String = s"$mgmtIp:$mgmtPort"
    override def toString = str
    override def canEqual(obj: Any): Boolean = obj.isInstanceOf[VtepEndPoint]
    override def equals(obj: Any): Boolean = obj match {
        case null => false
        case VtepEndPoint(null, p) => mgmtIp == null && mgmtPort == p
        case VtepEndPoint(ip, p) => ip.equals(mgmtIp) && p == mgmtPort
        case _ => false
    }
    override def hashCode: Int =
        mgmtPort + 31 * (if (mgmtIp != null) mgmtIp.hashCode() else 0)
}

object VtepEndPoint {
    def apply(ip: IPv4Addr, port: Int) = new VtepEndPoint(ip, port)
    def apply(ip: String, port: Int) = new VtepEndPoint(
        if (ip == null || ip.isEmpty) null else IPv4Addr.fromString(ip), port)
}
