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
package org.midonet.odp.ports;

import java.nio.ByteBuffer;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.DpPort;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.TCP;

/**
 * Description of a VxLAN tunnel datapath port.
 */
public class VxLanTunnelPort extends DpPort {

    public static short VXLAN_DEFAULT_DST_PORT = 4789;

    /*
     *    14B ethernet header length
     *  + 20B IPv4 header length
     *  +  8B UDP header length
     *  +  8B vxlan header length
     *  = 50B
     */
    public static final int TUNNEL_OVERHEAD = 50;

    private short dstPort;

    public VxLanTunnelPort(String name) {
        this(name, VXLAN_DEFAULT_DST_PORT);
    }

    public VxLanTunnelPort(String name, short dstPort) {
        super(name);
        setDestinationPort(dstPort);
    }

    public VxLanTunnelPort(String name, short dstPort, int portNo) {
        super(name, portNo);
        setDestinationPort(dstPort);
    }

    public int getDestinationPort() {
        return dstPort;
    }

    private void setDestinationPort(int dstPort) {
        TCP.ensurePortInRange(dstPort);
        this.dstPort = (short) dstPort;
    }

    public Type getType() {
        return Type.VXLan;
    }

    /** Serializes this VxLanTunnelPort object into a ByteBuffer. If the
     *  'options' instance variable is null and not serialized into the
     *  ByteBuffer, the datapath will return EINVAL to port_create requests. */
    @Override
    public void serializeInto(ByteBuffer buf) {
        super.serializeInto(buf);
        // OVS_VPORT_ATTR_OPTIONS is a nested netlink attribute. Here we
        // write its netlink header manually. The len field of the header is
        // equal to 2b + 2b for the header part + 8b for the attribute part.
        // The attribute is a single port field (u16) which we write with
        // padding (4b), and whose own header takes 4b. Note that the header
        // of the port attribute has its len field written without padding
        buf.putShort((short)12);
        buf.putShort(NetlinkMessage.nested(OpenVSwitch.Port.Attr.Options));
        // The datapath code checks for a u16 attribute written without
        // padding, therefore the len field of the header should be 6b.
        short portAttrId = OpenVSwitch.Port.VPortTunnelOptions.DstPort;
        NetlinkMessage.writeShortAttrNoPad(buf, portAttrId, dstPort);
    }

    @Override
    protected void deserializeFrom(ByteBuffer buf) {
        super.deserializeFrom(buf);
        short id = NetlinkMessage.nested(OpenVSwitch.Port.Attr.Options);
        int optionPos = NetlinkMessage.seekAttribute(buf, id);
        if (optionPos >= 0) {
            // skip the nested udp port header of the option attribute
            setDestinationPort(buf.getShort(optionPos + 4));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o))
            return false;

        @SuppressWarnings("unchecked") // safe cast
        VxLanTunnelPort that = (VxLanTunnelPort) o;

        return this.dstPort == that.dstPort;
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + dstPort;
    }


    @Override
    public String toString() {
        return "DpPort{" +
            "portNo=" + getPortNo() +
            ", type=" + getType() +
            ", name='" + getName() + '\'' +
            ", dstPort=" + dstPort +
            ", stats=" + getStats() +
            '}';
    }

    /** returns a new VxLanTunnelPort instance with default udp port value */
    public static VxLanTunnelPort make(String name) {
        return new VxLanTunnelPort(name);
    }

    /** returns a new VxLanTunnelPort instance with given udp port value */
    public static VxLanTunnelPort make(String name, int port) {
        return new VxLanTunnelPort(name, (short) port);
    }
}
