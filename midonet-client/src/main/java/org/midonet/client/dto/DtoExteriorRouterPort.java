/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;

@XmlRootElement
public class DtoExteriorRouterPort extends DtoRouterPort {
    private URI bgps;

    public URI getBgps() {
        return bgps;
    }

    public void setBgps(URI bgps) {
        this.bgps = bgps;
    }

    @Override
    public String getType() {
        return PortType.EXTERIOR_ROUTER;
    }
}
