/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.functional_test.topology;

import com.midokura.midolman.mgmt.data.dto.client.DtoBridge;
import com.midokura.midolman.mgmt.data.dto.client.DtoDhcpSubnet;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

/**
 * // TODO: Explain yourself.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/4/12
 */
public class Subnet {

    public SubnetHost.Builder newHostMapping(String ipAddress,
                                             String macAddress) {

        return new SubnetHost.Builder(mgmt, dto)
            .setIpAddress(ipAddress)
            .setMacAddress(macAddress);
    }

    public static class Builder {

        private MidolmanMgmt mgmt;
        private DtoBridge dtoBridge;

        private String defaultGateway;
        private String subnetPrefix;
        private int subNetLength;

        public Builder(MidolmanMgmt mgmt, DtoBridge dtoBridge) {
            this.mgmt = mgmt;
            this.dtoBridge = dtoBridge;
        }

        public Builder havingSubnet(String prefix, int len) {
            subnetPrefix = prefix;
            subNetLength = len;
            return this;
        }

        public Builder withSubnet(String prefix) {
            return havingSubnet(prefix, 24);
        }

        public Builder havingGateway(String defaultGateway) {
            this.defaultGateway = defaultGateway;
            return this;
        }

        public Subnet build() {
            DtoDhcpSubnet dhcpSubnet = new DtoDhcpSubnet();
            dhcpSubnet.setSubnetLength(subNetLength);
            dhcpSubnet.setSubnetPrefix(subnetPrefix);
            dhcpSubnet.setDefaultGateway(defaultGateway);

            dhcpSubnet = mgmt.addDhcpSubnet(dtoBridge, dhcpSubnet);
            return new Subnet(this.mgmt, dhcpSubnet);
        }
    }

    MidolmanMgmt mgmt;
    DtoDhcpSubnet dto;

    public Subnet(MidolmanMgmt mgmt, DtoDhcpSubnet dto) {
        this.mgmt = mgmt;
        this.dto = dto;
    }
}
