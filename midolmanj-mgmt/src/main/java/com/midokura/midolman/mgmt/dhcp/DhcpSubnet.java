/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.dhcp;

import com.midokura.midolman.mgmt.RelativeUriResource;
import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midonet.cluster.data.dhcp.Opt121;
import com.midokura.midonet.cluster.data.dhcp.Subnet;
import com.midokura.packets.IntIPv4;
import com.midokura.util.StringUtil;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@XmlRootElement
public class DhcpSubnet extends RelativeUriResource {
    @NotNull
    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN,
             message = "is an invalid IP format")
    private String subnetPrefix;

    @Min(0)
    @Max(32)
    private int subnetLength;

    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN,
             message = "is an invalid IP format")
    private String defaultGateway;

    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN,
             message = "is an invalid IP format")
    private String serverAddr;

    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN,
             message = "is an invalid IP format")
    private String dnsServerAddr;

    // Min has to be set to zero since default case, client sets
    // interface MTU to zero, we have to be able to accept that
    @Min(0)
    @Max(65536)
    private int interfaceMTU;

    private List<DhcpOption121> opt121Routes;

    /* Default constructor is needed for parsing/unparsing. */
    public DhcpSubnet() {
        opt121Routes = new ArrayList<DhcpOption121>();
    }

    public DhcpSubnet(String subnetPrefix, int subnetLength) {
        this.subnetPrefix = subnetPrefix;
        this.subnetLength = subnetLength;
    }

    public DhcpSubnet(Subnet subnet) {
        this(subnet.getSubnetAddr().toUnicastString(),
                subnet.getSubnetAddr().getMaskLength());

        IntIPv4 gway = subnet.getDefaultGateway();
        if (null != gway)
            this.setDefaultGateway(gway.toUnicastString());

        IntIPv4 srvAddr = subnet.getServerAddr();
        if (null != srvAddr)
            this.setServerAddr(srvAddr.toUnicastString());

        IntIPv4 dnsSrvAddr = subnet.getDnsServerAddr();
        if (null != dnsSrvAddr)
            this.setDnsServerAddr(dnsSrvAddr.toUnicastString());

        int intfMTU = subnet.getInterfaceMTU();
        if (intfMTU != 0)
            this.setInterfaceMTU(intfMTU);

        List<DhcpOption121> routes = new ArrayList<DhcpOption121>();
        if (null != subnet.getOpt121Routes()) {
            for (Opt121 opt : subnet.getOpt121Routes())
                routes.add(new DhcpOption121(opt));
        }
        this.setOpt121Routes(routes);
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

    public String getServerAddr() {
        return serverAddr;
    }

    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public String getDnsServerAddr() {
        return dnsServerAddr;
    }

    public void setDnsServerAddr(String dnsServerAddr) {
        this.dnsServerAddr = dnsServerAddr;
    }

    public int getInterfaceMTU() {
        return interfaceMTU;
    }

    public void setInterfaceMTU(int interfaceMTU) {
        this.interfaceMTU = interfaceMTU;
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

    public Subnet toData() {
        List<Opt121> routes = new ArrayList<Opt121>();
        if (null != getOpt121Routes()) {
            for (DhcpOption121 opt : getOpt121Routes())
                routes.add(opt.toData());
        }

        IntIPv4 subnetAddr = IntIPv4.fromString(subnetPrefix, subnetLength);
        IntIPv4 gtway = (null == defaultGateway) ? null
                : IntIPv4.fromString(defaultGateway);
        IntIPv4 srvAddr = (null == serverAddr) ? null : IntIPv4.fromString(serverAddr);
        IntIPv4 dnsSrvAddr = (null == dnsServerAddr) ? null : IntIPv4.fromString(dnsServerAddr);

        return new Subnet()
                .setDefaultGateway(gtway)
                .setSubnetAddr(subnetAddr)
                .setOpt121Routes(routes)
                .setServerAddr(srvAddr)
                .setDnsServerAddr(dnsSrvAddr)
                .setInterfaceMTU((short)interfaceMTU);
    }

    @Override
    public String toString() {
        return "DhcpSubnet{" + "subnetPrefix='" + subnetPrefix + '\''
                + ", subnetLength=" + subnetLength + ", defaultGateway='"
                + defaultGateway + '\'' + ", serverAddr='" + serverAddr + '\''
                + ", dnsServerAddr='" + dnsServerAddr + '\''
                + ", interfaceMTU='" + interfaceMTU + '\''
                + ", opt121Routes=" + opt121Routes
                + '}';
    }
}
