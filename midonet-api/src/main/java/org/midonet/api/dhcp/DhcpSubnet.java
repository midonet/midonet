/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.api.dhcp;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.midonet.api.RelativeUriResource;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.cluster.data.dhcp.Opt121;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.packets.IntIPv4;
import org.midonet.packets.IPv4Subnet;
import org.midonet.util.StringUtil;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlRootElement;

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

    private List<String> dnsServerAddrs;

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

        if (null != subnet.getDnsServerAddrs()) {
            List<String> dnsSrvAddrs = new ArrayList<String>();
            for (IntIPv4 ipAddr : subnet.getDnsServerAddrs()) {
                dnsSrvAddrs.add(ipAddr.toUnicastString());
            }
            this.setDnsServerAddrs(dnsSrvAddrs);
        }

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

    public List<String> getDnsServerAddrs() {
        return dnsServerAddrs;
    }

    public void setDnsServerAddrs(List<String> dnsServerAddrs) {
        this.dnsServerAddrs = dnsServerAddrs;
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

        List<IntIPv4> dnsSrvAddrs = null;
        if (null != getDnsServerAddrs()) {
            dnsSrvAddrs = new ArrayList<IntIPv4>();
            for (String ipAddr : getDnsServerAddrs())
                dnsSrvAddrs.add(IntIPv4.fromString(ipAddr));
        }

        IntIPv4 subnetAddr = IntIPv4.fromString(subnetPrefix, subnetLength);
        IntIPv4 gtway = (null == defaultGateway) ? null
                : IntIPv4.fromString(defaultGateway);
        IntIPv4 srvAddr = (null == serverAddr) ? null : IntIPv4.fromString(serverAddr);

        return new Subnet()
                .setDefaultGateway(IntIPv4.toIPv4Subnet(gtway))
                .setSubnetAddr(IntIPv4.toIPv4Subnet(subnetAddr))
                .setOpt121Routes(routes)
                .setServerAddr(srvAddr)
                .setDnsServerAddrs(dnsSrvAddrs)
                .setInterfaceMTU((short)interfaceMTU);
    }

    @Override
    public String toString() {
        return "DhcpSubnet{" + "subnetPrefix='" + subnetPrefix + '\''
                + ", subnetLength=" + subnetLength + ", defaultGateway='"
                + defaultGateway + '\'' + ", serverAddr='" + serverAddr + '\''
                + ", dnsServerAddrs='" + dnsServerAddrs + '\''
                + ", interfaceMTU='" + interfaceMTU + '\''
                + ", opt121Routes=" + opt121Routes
                + '}';
    }
}
