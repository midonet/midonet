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
package org.midonet.odp.flows;

import java.util.Arrays;

/**
 * This FlowKey is not supported by Netlink. FlowMatch is aware of this and
 * will report that the match is not Netlink-compatible so that the
 * FlowController never installs a kernel flow with this key. Otherwise it's
 * functional and can be used for WildcardFlows.
 *
 * By making this class implement FlowKey.UserSpaceOnly we ensure that flows
 * that require matching on this key will never be installed in the kernel.
 */
public class FlowKeyICMPError extends FlowKeyICMP
                              implements FlowKey.UserSpaceOnly {
    private byte[] icmp_data = null;

    FlowKeyICMPError(byte type, byte code, byte[] data) {
        super(type, code);
        icmp_data = data;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyICMPError that = (FlowKeyICMPError) o;

        return super.equals(that) && Arrays.equals(icmp_data, that.icmp_data);
    }

    @Override
    public int hashCode() {
        return 35 * super.hashCode() + Arrays.hashCode(icmp_data);
    }

    @Override
    public String toString() {
        return "ICMPError{type=0x" + Integer.toHexString(icmp_type)
             + ", code=" + icmp_code
             + ", data=" + Arrays.toString(icmp_data) + "}";
    }

    public byte[] getIcmpData() {
        return (icmp_data == null) ? null
                                   : Arrays.copyOf(icmp_data, icmp_data.length);
    }
}
