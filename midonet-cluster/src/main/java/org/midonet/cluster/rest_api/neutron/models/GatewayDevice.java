package org.midonet.cluster.rest_api.neutron.models;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.*;
import org.midonet.cluster.models.Neutron;
import org.midonet.packets.IPv4Addr;

@ZoomClass(clazz = Neutron.GatewayDevice.class)
public class GatewayDevice extends ZoomObject {

    @JsonProperty("id")
    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "type")
    public GatewayType type;

    @JsonProperty("resource_id")
    @ZoomField(name = "resource_id")
    public UUID resourceId;

    @JsonProperty("tunnel_ips")
    @ZoomField(name = "tunnel_ips")
    public List<IPv4Addr> tunnelIps;

    @JsonProperty("management_ip")
    @ZoomField(name = "management_ip")
    public IPv4Addr managementIp;

    @JsonProperty("management_port")
    @ZoomField(name = "management_port")
    public Integer managementPort;

    @JsonProperty("management_protocol")
    @ZoomField(name = "management_protocol")
    public GatewayDeviceProtocol managementProtocol;

    @JsonProperty("remote_mac_entries")
    @ZoomField(name = "remote_mac_entries")
    public List<RemoteMacEntry> remoteMacEntries;

    @ZoomEnum(clazz = Neutron.GatewayDevice.GatewayType.class)
    public enum GatewayType {
        @ZoomEnumValue("ROUTER_VTEP") ROUTER_VTEP,
        @ZoomEnumValue("HW_VTEP") HW_VTEP;
    }

    @ZoomEnum(clazz = Neutron.GatewayDevice.GatewayDeviceProtocol.class)
    public enum GatewayDeviceProtocol {
        @ZoomEnumValue("OVSDB") OVSDB;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        GatewayDevice that = (GatewayDevice) o;

        return Objects.equals(id, that.id) &&
               Objects.equals(resourceId, that.resourceId) &&
               Objects.equals(tunnelIps, that.tunnelIps) &&
               Objects.equals(managementIp, that.managementIp) &&
               Objects.equals(managementPort, that.managementPort) &&
               Objects.equals(managementProtocol, that.managementProtocol) &&
               Objects.equals(remoteMacEntries, that.remoteMacEntries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("resourceId", resourceId)
            .add("tunnelIps", tunnelIps)
            .add("managementIp", managementIp)
            .add("managementPort", managementPort)
            .add("managementProtocol", managementProtocol)
            .add("remoteMacEntries", remoteMacEntries)
            .toString();
    }
}
