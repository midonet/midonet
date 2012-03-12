/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.client.DtoDhcpOption121;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.state.BridgeDhcpZkManager.Opt121;

@XmlRootElement
public class DhcpOption121 extends DtoDhcpOption121 {

    /* Default constructor for parsing. */
    public DhcpOption121() {
    }

    public DhcpOption121(String destinationPrefix, int destinationLength,
                         String gatewayAddr) {
        super(destinationPrefix, destinationLength, gatewayAddr);
    }

    // These methods cannot be added to DtoDhcpOption121 because client DTOs
    // should not depend on other Midolman packages.
    public Opt121 toOpt121() {
        return new Opt121(
                IntIPv4.fromString(destinationPrefix, destinationLength),
                IntIPv4.fromString(gatewayAddr));
    }

    public static DhcpOption121 fromOpt121(Opt121 opt) {
        return new DhcpOption121(opt.getRtDstSubnet().toUnicastString(),
                opt.getRtDstSubnet().getMaskLength(),
                opt.getGateway().toString());
    }
}
