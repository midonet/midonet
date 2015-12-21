package org.midonet.cluster.rest_api.neutron.models;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;

@ZoomClass(clazz = Neutron.L2Gateway.class)
public class L2Gateway extends ZoomObject {

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "name")
    public String name;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "devices")
    public List<L2GatewayDevice> devices;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        L2Gateway that = (L2Gateway) o;

        return Objects.equals(id, that.id) &&
               Objects.equals(name, that.name) &&
               Objects.equals(tenantId, that.tenantId) &&
               Objects.equals(devices, that.devices);
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
            .add("name", name)
            .add("tenantId", tenantId)
            .add("devices", devices)
            .toString();
    }
}
