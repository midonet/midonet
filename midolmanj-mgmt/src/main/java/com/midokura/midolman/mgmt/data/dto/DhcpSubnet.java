/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.packets.IntIPv4;
import com.midokura.midolman.state.zkManagers.BridgeDhcpZkManager.Opt121;
import com.midokura.midolman.state.zkManagers.BridgeDhcpZkManager.Subnet;

@XmlRootElement
public class DhcpSubnet extends RelativeUriResource {
    private String subnetPrefix;
    private int subnetLength;
    private String defaultGateway;
    private List<DhcpOption121> opt121Routes;

    /* Default constructor is needed for parsing/unparsing. */
    public DhcpSubnet() {
        opt121Routes = new ArrayList<DhcpOption121>();
    }

    public DhcpSubnet(String subnetPrefix, int subnetLength) {
        this.subnetPrefix = subnetPrefix;
        this.subnetLength = subnetLength;
    }

    public String getSubnetPrefix() {
        return subnetPrefix;
    }

    public void setSubnetPrefix(String subnetPrefix) {
        this.subnetPrefix = subnetPrefix;
    }

    public int getSubnetLength() {
        return subnetLength;
    }

    public void setSubnetLength(int subnetLength) {
        this.subnetLength = subnetLength;
    }

    public String getDefaultGateway() {
        return defaultGateway;
    }

    public void setDefaultGateway(String defaultGateway) {
        this.defaultGateway = defaultGateway;
    }

    public List<DhcpOption121> getOpt121Routes() {
        return opt121Routes;
    }

    public void setOpt121Routes(List<DhcpOption121> opt121Routes) {
        this.opt121Routes = opt121Routes;
    }

    public URI getHosts() {
        if (getUri() != null) {
            return ResourceUriBuilder.getDhcpHosts(getUri());
        } else {
            return null;
        }
    }

    public URI getUri() {
        if (getParentUri() != null && subnetPrefix != null) {
            return ResourceUriBuilder.getBridgeDhcp(getParentUri(),
                    IntIPv4.fromString(subnetPrefix, subnetLength));
        } else {
            return null;
        }
    }

    public static DhcpSubnet fromSubnet(Subnet subnet) {
        List<DhcpOption121> routes = new ArrayList<DhcpOption121>();
        if (null != subnet.getOpt121Routes()) {
            for (Opt121 opt : subnet.getOpt121Routes())
                routes.add(DhcpOption121.fromOpt121(opt));
        }
        DhcpSubnet dtoSub = new DhcpSubnet(subnet.getSubnetAddr()
                .toUnicastString(), subnet.getSubnetAddr().getMaskLength());
        IntIPv4 gway = subnet.getDefaultGateway();
        if (null != gway)
            dtoSub.setDefaultGateway(gway.toUnicastString());
        dtoSub.setOpt121Routes(routes);
        return dtoSub;
    }

    public Subnet toSubnet() {
        List<Opt121> routes = new ArrayList<Opt121>();
        if (null != getOpt121Routes()) {
            for (DhcpOption121 opt : getOpt121Routes())
                routes.add(opt.toOpt121());
        }
        return new Subnet(IntIPv4.fromString(subnetPrefix, subnetLength),
                null == defaultGateway ? null
                        : IntIPv4.fromString(defaultGateway), routes);
    }

    @Override
    public String toString() {
        return "DhcpSubnet{" + "subnetPrefix='" + subnetPrefix + '\''
                + ", subnetLength=" + subnetLength + ", defaultGateway='"
                + defaultGateway + '\'' + ", opt121Routes=" + opt121Routes
                + '}';
    }
}
