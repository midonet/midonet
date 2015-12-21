package org.midonet.cluster.rest_api.neutron.models;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.*;
import org.midonet.cluster.models.Neutron;
import org.midonet.packets.IPv4Addr;

@ZoomClass(clazz = Neutron.L2GatewayConnection.L2Gateway.GatewayDevice.class)
public class GatewayDevice extends ZoomObject {

    @JsonProperty("id")
    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("resource_id")
    @ZoomField(name = "resource_id")
    public UUID resourceId;

    @JsonProperty("gateway_type")
    @ZoomField(name = "gateway_type")
    public GatewayType gatewayType;

    @JsonProperty("tunnel_ips")
    @ZoomField(name = "tunnel_ips")
    public List<IPv4Addr> tunnelIps;

    @ZoomEnum(clazz = Neutron.L2GatewayConnection.L2Gateway.GatewayDevice.GatewayType.class)
    public enum GatewayType {
        @ZoomEnumValue("ROUTER") ROUTER;
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
               Objects.equals(gatewayType, that.gatewayType) &&
               Objects.equals(tunnelIps, that.tunnelIps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, resourceId, gatewayType, tunnelIps);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("resourceId", resourceId)
            .add("gatewayType", gatewayType)
            .add("tunnelIps", tunnelIps)
            .toString();
    }
}
