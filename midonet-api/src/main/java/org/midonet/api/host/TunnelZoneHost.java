/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.api.host.validation.IsValidHostId;
import org.midonet.api.host.validation.IsValidTunnelZoneId;
import org.midonet.api.host.validation.IsUniqueTunnelZoneMember;
import org.midonet.api.host.TunnelZoneHost.TunnelZoneHostUnique;
import org.midonet.cluster.data.TunnelZone.HostConfig;
import org.midonet.packets.IntIPv4;
import org.midonet.util.StringUtil;

import javax.validation.GroupSequence;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/**
 * Class representing Tunnel zone - host mapping.
 */
@IsUniqueTunnelZoneMember(groups = TunnelZoneHostUnique.class)
@XmlRootElement
public class TunnelZoneHost extends UriResource {

    @IsValidTunnelZoneId
    private UUID tunnelZoneId;

    @IsValidHostId
    private UUID hostId;

    @NotNull
    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN,
            message = "is an invalid IP format")
    private String ipAddress;

    /**
     * Constructor.
     */
    public TunnelZoneHost() {
    }

    public TunnelZoneHost(UUID tunnelZoneId, HostConfig data) {
        this(tunnelZoneId, UUID.fromString(data.getId().toString()));
        this.ipAddress = data.getIp().toString();
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

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public HostConfig toData() {
        HostConfig data = new HostConfig(null, new HostConfig.Data());
        setData(data);
        return data;
    }

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
        data.setIp(IntIPv4.fromString(ipAddress));
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
        return "TunnelZoneHost{" +
                "tunnelZoneId=" + tunnelZoneId +
                ", hostId=" + hostId +
                ", ipAddress='" + ipAddress + '\'' +
                '}';
    }

    /**
     * Interface used for validating a tunnel zone on creates.
     */
    public interface TunnelZoneHostUnique {
    }

    /**
     * Interface that defines the ordering of validation groups for tunnel zone
     * create.
     */
    @GroupSequence({ Default.class, TunnelZoneHostUnique.class })
    public interface TunnelZoneHostCreateGroupSequence {
    }
}
