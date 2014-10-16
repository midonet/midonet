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
package org.midonet.brain.southbound.vtep;

import java.util.Objects;

import org.midonet.packets.IPv4Addr;

public class VtepEndPoint {

    public final IPv4Addr mgmtIp;
    public final int mgmtPort;

    public VtepEndPoint(IPv4Addr mgmtIp, int mgmtPort) {
        this.mgmtIp = mgmtIp;
        this.mgmtPort = mgmtPort;
    }

    public static VtepEndPoint apply(String mgmtIp, int mgmtPort) {
        return new VtepEndPoint(IPv4Addr.apply(mgmtIp), mgmtPort);
    }

    @Override
    public String toString() {
        return String.format("%s:%s", mgmtIp, mgmtPort);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (null == obj || getClass() != obj.getClass()) return false;

        VtepEndPoint ep = (VtepEndPoint) obj;
        return Objects.equals(mgmtIp, ep.mgmtIp) &&
               (mgmtPort == ep.mgmtPort);
    }

    @Override
    public int hashCode() {
        int result = mgmtIp != null ? mgmtIp.hashCode() : 0;
        result = 31 * result + mgmtPort;
        return result;
    }
}
