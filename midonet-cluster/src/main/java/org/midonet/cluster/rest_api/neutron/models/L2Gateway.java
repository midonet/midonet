package org.midonet.cluster.rest_api.neutron.models;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;

@ZoomClass(clazz = Neutron.L2GatewayConnection.L2Gateway.class)
public class L2Gateway extends ZoomObject {

    @JsonProperty("name")
    @ZoomField(name = "name")
    public String name;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @JsonProperty("device_id")
    @ZoomField(name = "device_id")
    public UUID deviceId;

    @JsonProperty("gateway_device")
    @ZoomField(name = "gateway_device")
    public GatewayDevice gatewayDevice;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        L2Gateway that = (L2Gateway) o;

        return Objects.equals(name, that.name) &&
               Objects.equals(tenantId, that.tenantId) &&
               Objects.equals(deviceId, that.deviceId) &&
               Objects.equals(gatewayDevice, that.gatewayDevice);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, tenantId, deviceId, gatewayDevice);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("name", name)
            .add("tenantId", tenantId)
            .add("deviceId", deviceId)
            .add("gatewayDevice", gatewayDevice)
            .toString();
    }
}
