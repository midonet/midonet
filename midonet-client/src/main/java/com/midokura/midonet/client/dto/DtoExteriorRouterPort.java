/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;

@XmlRootElement
public class DtoExteriorRouterPort extends DtoRouterPort {
    private URI bgps;
    private URI vpns;

    public URI getBgps() {
        return bgps;
    }

    public void setBgps(URI bgps) {
        this.bgps = bgps;
    }

    public URI getVpns() {
        return vpns;
    }

    public void setVpns(URI vpns) {
        this.vpns = vpns;
    }

    @Override
    public String getType() {
        return PortType.EXTERIOR_ROUTER;
    }
}
