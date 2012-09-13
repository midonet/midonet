/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host;

import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midolman.mgmt.UriResource;
import com.midokura.midolman.mgmt.host.validation.IsValidHostId;
import com.midokura.midolman.mgmt.host.validation.IsValidTunnelZoneId;
import com.midokura.midonet.cluster.data.TunnelZone.HostConfig;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/**
 * Class representing Tunnel zone - host mapping.
 */
@XmlRootElement
public abstract class TunnelZoneHost extends UriResource {

    @IsValidTunnelZoneId
    private UUID tunnelZoneId;

    @IsValidHostId
    private UUID hostId;

    /**
     * Constructor.
     */
    public TunnelZoneHost() {
    }

    public TunnelZoneHost(UUID tunnelZoneId, HostConfig data) {
        this(tunnelZoneId, UUID.fromString(data.getId().toString()));
    }

    /**
     * Constructor
     *
     * @param tunnelZoneId
     *            ID of the tunnel zone.
     * @param hostId
     *            ID of the host
     */
    public TunnelZoneHost(UUID tunnelZoneId, UUID hostId) {
        this.tunnelZoneId = tunnelZoneId;
        this.hostId = hostId;
    }

    public abstract HostConfig toData();

    /**
     * Get tunnel zone ID.
     *
     * @return Tunnel Zone ID.
     */
    public UUID getTunnelZoneId() {
        return tunnelZoneId;
    }

    /**
     * Set tunnel zone ID.
     *
     * @param tunnelZoneId
     *            ID of the tunnel zone.
     */
    public void setTunnelZoneId(UUID tunnelZoneId) {
        this.tunnelZoneId = tunnelZoneId;
    }

    /**
     * Get host ID
     *
     * @return Host ID.
     */
    public UUID getHostId() {
        return hostId;
    }

    /**
     * Set host ID.
     *
     * @param hostId
     *            Id of the host.
     */
    public void setHostId(UUID hostId) {
        this.hostId = hostId;
    }

    protected void setData(HostConfig data) {
        data.setId(hostId);
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && tunnelZoneId != null
                && hostId != null) {
            return ResourceUriBuilder.getTunnelZoneHost(getBaseUri(),
                    tunnelZoneId, hostId);
        } else {
            return null;
        }
    }

    @Override
    public String toString() {
        return "tunnelZoneId=" + tunnelZoneId + ", hostId=" + hostId;
    }
}
