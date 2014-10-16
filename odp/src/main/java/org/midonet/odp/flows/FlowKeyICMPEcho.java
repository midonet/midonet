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

/**
 * This FlowKey is not supported by Netlink. FlowMatch is aware of this and
 * will report that the match is not Netlink-compatible so that the
 * FlowController never installs a kernel flow with this key. Otherwise it's
 * functional and can be used for WildcardFlows.
 *
 * By making this class implement FlowKey.UserSpaceOnly we ensure that flows
 * that require matching on this key will never be installed in the kernel.
 */
public class FlowKeyICMPEcho extends FlowKeyICMP
                             implements FlowKey.UserSpaceOnly {
    private short icmp_id;

    FlowKeyICMPEcho(byte type, byte code, short icmpId) {
        super(type, code);
        icmp_id = icmpId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyICMPEcho that = (FlowKeyICMPEcho) o;

        return super.equals(that) && (this.icmp_id == that.icmp_id);
    }

    @Override
    public int hashCode() {
        return 33 * super.hashCode() + icmp_id;
    }

    @Override
    public int connectionHash() {
        return hashCode();
    }

    @Override
    public String toString() {
        return String.format("ICMPEcho{type=0x%X, code=%d, " +
                             "id=%d}", icmp_type, icmp_code, icmp_id);
    }

    public short getIdentifier() {
        return icmp_id;
    }
}

