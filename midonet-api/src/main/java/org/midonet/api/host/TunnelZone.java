/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host;

import org.midonet.api.host.validation.UniqueTunnelZoneName;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;

import javax.validation.GroupSequence;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/**
 * Class representing Tunnel zone.
 */
@XmlRootElement
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CapwapTunnelZone.class,
                name = TunnelZoneType.CAPWAP),
        @JsonSubTypes.Type(value = GreTunnelZone.class,
                name = TunnelZoneType.GRE),
        @JsonSubTypes.Type(value = IpsecTunnelZone.class,
                name = TunnelZoneType.IPSEC)})
public abstract class TunnelZone extends UriResource {

    public static final int MIN_TUNNEL_ZONE_NAME_LEN = 1;
    public static final int MAX_TUNNEL_ZONE_NAME_LEN = 255;

    @NotNull(groups = TunnelZoneUpdateGroup.class)
    private UUID id;

    @NotNull
    @Size(min = MIN_TUNNEL_ZONE_NAME_LEN, max = MAX_TUNNEL_ZONE_NAME_LEN)
    @UniqueTunnelZoneName
    private String name;

    /**
     * Constructor.
     */
    public TunnelZone() {
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the tunnel zone.
     * @param name
     *            Name of the tunnel zone.
     */
    public TunnelZone(UUID id, String name) {
        super();
        this.id = id;
        this.name = name;
    }

    /**
     * Tunnel zone constructor
     *
     * @param tunnelZoneData
     *            TunnelZone data object
     */
    public TunnelZone(org.midonet.cluster.data.TunnelZone
                              tunnelZoneData) {
        this(UUID.fromString(tunnelZoneData.getId().toString()),
                tunnelZoneData.getName());
    }

    /**
     * Get tunnel zone ID.
     *
     * @return Bridge ID.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Set tunnel zone ID.
     *
     * @param id
     *            ID of the tunnel zone.
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * Get tunnel zone name.
     *
     * @return Bridge name.
     */
    public String getName() {
        return name;
    }

    /**
     * Set tunnel zone name.
     *
     * @param name
     *            Name of the tunnel zone.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return　The tunnel zone type
     */
    public abstract String getType();

    public abstract org.midonet.cluster.data.TunnelZone toData();

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getTunnelZone(getBaseUri(), id);
        } else {
            return null;
        }
    }

    public URI getHosts() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getTunnelZoneHosts(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * Convert this object to tunnel zone data object
     *
     * @return Tunnel Zone data object
     */
    public void setConfig(org.midonet.cluster.data.TunnelZone data) {
        data.setId(this.id);
        data.setName(this.name);
    }

    @Override
    public String toString() {
        return "id=" + id + ", name=" + name;
    }

    /**
     * Interface used for validating a tunnel zone on updates.
     */
    public interface TunnelZoneUpdateGroup {
    }

    /**
     * Interface used for validating a tunnel zone on creates.
     */
    public interface TunnelZoneCreateGroup {
    }

    /**
     * Interface that defines the ordering of validation groups for tunnel zone
     * create.
     */
    @GroupSequence({ Default.class, TunnelZoneCreateGroup.class })
    public interface TunnelZoneCreateGroupSequence {
    }

    /**
     * Interface that defines the ordering of validation groups for tunnel zone
     * update.
     */
    @GroupSequence({ Default.class, TunnelZoneUpdateGroup.class })
    public interface TunnelZoneUpdateGroupSequence {
    }
}
