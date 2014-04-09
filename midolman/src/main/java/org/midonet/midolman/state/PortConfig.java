// Copyright 2012 Midokura Inc.

package org.midonet.midolman.state;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;
import org.midonet.midolman.state.PortDirectory.BridgePortConfig;
import org.midonet.midolman.state.PortDirectory.InteriorBridgePortConfig;
import org.midonet.midolman.state.PortDirectory.ExteriorBridgePortConfig;
import org.midonet.midolman.state.PortDirectory.RouterPortConfig;
import org.midonet.midolman.state.PortDirectory.InteriorRouterPortConfig;
import org.midonet.midolman.state.PortDirectory.ExteriorRouterPortConfig;
import org.midonet.midolman.state.PortDirectory.VxLanPortConfig;
import org.midonet.midolman.state.zkManagers.BaseConfig;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
    property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = BridgePortConfig.class,
        name = "BridgePort"),
    @JsonSubTypes.Type(value = RouterPortConfig.class,
        name = "RouterPort"),
    @JsonSubTypes.Type(value = ExteriorBridgePortConfig.class,
        name = "ExteriorBridgePort"),
    @JsonSubTypes.Type(value = ExteriorRouterPortConfig.class,
        name = "ExteriorRouterPort"),
    @JsonSubTypes.Type(value = InteriorBridgePortConfig.class,
        name = "InteriorBridgePort"),
    @JsonSubTypes.Type(value = InteriorRouterPortConfig.class,
        name = "InteriorRouterPort"),
    @JsonSubTypes.Type(value = VxLanPortConfig.class,
        name = "VxLanPort")})
public abstract class PortConfig extends BaseConfig {

    PortConfig(UUID device_id) {
        super();
        this.device_id = device_id;
    }
    // Default constructor for the Jackson deserialization.
    PortConfig() { super(); }
    public UUID device_id;
    public boolean adminStateUp;
    public UUID inboundFilter;
    public UUID outboundFilter;
    public Set<UUID> portGroupIDs;
    public int tunnelKey;
    public Map<String, String> properties = new HashMap<String, String>();

    public UUID hostId;
    public String interfaceName;
    public UUID peerId;
    public String v1ApiType;

    public String getV1ApiType() { return v1ApiType; }
    public void setV1ApiType(String type) { v1ApiType=type; }

    // Custom accessors for Jackson serialization
    public UUID getHostId() { return hostId; }
    public void setHostId(UUID hostId) {
        this.hostId = hostId;
    }
    public String getInterfaceName() { return interfaceName; }
    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
    }
    public UUID getPeerId() { return peerId; }
    public void setPeerId(UUID pId) { peerId = pId; }

    @JsonIgnore
    public boolean isInterior() { return peerId != null; }
    @JsonIgnore
    public boolean isExterior() { return hostId != null; }
    @JsonIgnore
    public boolean isUnplugged() { return !isInterior() && !isExterior(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PortConfig that = (PortConfig) o;

        if (tunnelKey != that.tunnelKey) return false;
        if (device_id != null
                ? !device_id.equals(that.device_id) : that.device_id != null)
            return false;
        if (inboundFilter != null
                ? !inboundFilter.equals(that.inboundFilter)
                : that.inboundFilter != null)
            return false;
        if (outboundFilter != null
                ? !outboundFilter.equals(that.outboundFilter)
                : that.outboundFilter != null)
            return false;
        if (portGroupIDs != null ? !portGroupIDs.equals(that.portGroupIDs)
                : that.portGroupIDs != null)
            return false;
        if (v1ApiType != null ? v1ApiType.equals(that.v1ApiType)
                : that.v1ApiType != null)
            return false;
        return
            hostId == null ? that.hostId == null : hostId.equals(that.hostId)
            && interfaceName == null ?
               that.interfaceName == null :
               interfaceName.equals(that.interfaceName)
            && peerId == null? that.peerId == null :
               peerId.equals(that.peerId)
            && adminStateUp == that.adminStateUp;
    }

    @Override
    public int hashCode() {
        int result = device_id != null ? device_id.hashCode() : 0;
        result = 31 * result +
                (inboundFilter != null ? inboundFilter.hashCode() : 0);
        result = 31 * result +
                (outboundFilter != null ? outboundFilter.hashCode() : 0);
        result = 31 * result +
                (portGroupIDs != null ? portGroupIDs.hashCode() : 0);
        result = 31 * result + tunnelKey;
        result = 31 * result + (peerId != null ? peerId.hashCode() : 0);
        result = 31 * result + (hostId != null ? hostId.hashCode() : 0);
        result = 31 * result +
                 (interfaceName != null ? interfaceName.hashCode() : 0);
        result = 31 * result +
            (v1ApiType != null ? v1ApiType.hashCode() : 0);
        result = 31 * result + Boolean.valueOf(adminStateUp).hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "PortConfig{ device_id=," + device_id +
                ", hostId=" + hostId +
                ", interfaceName=" + interfaceName +
                ", peerId=" + peerId +
                ", adminStateUp=" + adminStateUp + '}';
    }
}
