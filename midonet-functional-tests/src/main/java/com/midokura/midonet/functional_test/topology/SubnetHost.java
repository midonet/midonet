/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.functional_test.topology;

import com.midokura.midonet.client.dto.DtoDhcpHost;
import com.midokura.midonet.client.dto.DtoDhcpSubnet;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

/**
 * // TODO: Explain yourself.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/4/12
 */
public class SubnetHost {

    public static class Builder {

        private MidolmanMgmt mgmt;
        private DtoDhcpSubnet dtoSubnet;

        private String ipAddress;
        private String macAddress;

        public Builder(MidolmanMgmt mgmt, DtoDhcpSubnet dtoSubnet) {
            this.mgmt = mgmt;
            this.dtoSubnet = dtoSubnet;
        }

        public SubnetHost build() {
            DtoDhcpHost host = new DtoDhcpHost();

            host.setIpAddr(ipAddress);
            host.setMacAddr(macAddress);

            host = mgmt.addDhcpSubnetHost(dtoSubnet, host);

            return new SubnetHost(mgmt, host);
        }

        public Builder setIpAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        public Builder setMacAddress(String macAddress) {
            this.macAddress = macAddress;
            return this;
        }
    }

    MidolmanMgmt mgmt;
    DtoDhcpHost dtoHost;

    public SubnetHost(MidolmanMgmt mgmt, DtoDhcpHost dtoHost) {
        this.mgmt = mgmt;
        this.dtoHost = dtoHost;
    }
}
