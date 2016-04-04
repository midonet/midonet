package org.midonet.cluster.rest_api.neutron.models;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;

@ZoomClass(clazz = Neutron.NeutronBgpSpeaker.class)
public class BgpSpeaker extends ZoomObject {
    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "name")
    public String name;

    @JsonProperty("local_as")
    @ZoomField(name = "local_as")
    public Integer localAs;

    @JsonProperty("ip_version")
    @ZoomField(name = "ip_version")
    public Integer ipVersion;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        BgpSpeaker that = (BgpSpeaker) o;

        return Objects.equals(id, that.id) &&
               Objects.equals(tenantId, that.tenantId) &&
               Objects.equals(name, that.name) &&
               Objects.equals(localAs, that.localAs) &&
               Objects.equals(ipVersion, that.ipVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, tenantId, name, localAs, ipVersion);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("tenantId", tenantId)
            .add("name", name)
            .add("localAs", localAs)
            .add("ipVersion", ipVersion)
            .toString();
    }
}
