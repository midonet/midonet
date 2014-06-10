/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.network;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.packets.IPv4Addr;

import java.net.URI;
import java.util.List;

public class VxLanPort extends Port {

    private String mgmtIpAddr;
    private int mgmtPort;
    private int vni;

    public VxLanPort(org.midonet.cluster.data.ports.VxLanPort vxLanPort) {
        super(vxLanPort);
        mgmtIpAddr = vxLanPort.getMgmtIpAddr().toString();
        mgmtPort = vxLanPort.getMgmtPort();
        vni = vxLanPort.getVni();
    }

    @Override
    public URI getDevice() {
        if (getBaseUri() == null || deviceId == null)
            return null;
        return ResourceUriBuilder.getBridge(getBaseUri(), deviceId);
    }

    @Override
    public org.midonet.cluster.data.ports.VxLanPort toData() {
        org.midonet.cluster.data.ports.VxLanPort vxLanPort =
                new org.midonet.cluster.data.ports.VxLanPort();
        setConfig(vxLanPort);
        return vxLanPort;
    }

    public void setConfig(org.midonet.cluster.data.ports.VxLanPort vxLanPort) {
        super.setConfig(vxLanPort);
        vxLanPort.setMgmtIpAddr(IPv4Addr.fromString(mgmtIpAddr));
        vxLanPort.setMgmtPort(mgmtPort);
        vxLanPort.setVni(vni);
    }

    @Override
    public boolean isLinkable(Port port) {
        return false;
    }

    @Override
    public String getType() {
        return PortType.VXLAN;
    }

    public String getMgmtIpAddr() {
        return mgmtIpAddr;
    }

    public void setMgmtIpAddr(String mgmtIpAddr) {
        this.mgmtIpAddr = mgmtIpAddr;
    }

    public int getMgmtPort() {
        return mgmtPort;
    }

    public void setMgmtPort(int mgmtPort) {
        this.mgmtPort = mgmtPort;
    }

    public int getVni() {
        return vni;
    }

    public void setVni(int vni) {
        this.vni = vni;
    }

    public URI getBindings() {
        return (getBaseUri() == null || getId() == null) ? null :
                ResourceUriBuilder.getVxLanPortBindings(getBaseUri(), getId());
    }


}
