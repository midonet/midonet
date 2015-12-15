package org.midonet.cluster.rest_api.neutron.models;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;

@ZoomClass(clazz = org.midonet.cluster.models.Neutron.IkePolicy.class)
public class IkePolicy extends ZoomObject {
    // TODO: this field and the enum is pointless, leave out and add whenever
    // needed?
    @ZoomEnum(clazz = Neutron.IkePolicy.Phase1NegotiationMode.class)
    public enum Phase1NegotiationMode {
        @ZoomEnumValue("MAIN") MAIN;

        @JsonCreator
        @SuppressWarnings("unused")
        public static Phase1NegotiationMode forValue(String v) {
            return valueOf(IPSecSiteConnection.normalizeIpSecEnumString(v));
        }
    }

    @ZoomEnum(clazz = Neutron.IkePolicy.IkeVersion.class)
    public enum IkeVersion {
        @ZoomEnumValue("V1") V1,
        @ZoomEnumValue("V2") V2;

        @JsonCreator
        @SuppressWarnings("unused")
        public static IkeVersion forValue(String v) {
            return valueOf(IPSecSiteConnection.normalizeIpSecEnumString(v));
        }
    }

    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "description")
    public String description;

    @JsonProperty("auth_algorithm")
    @ZoomField(name = "auth_algorithm")
    public IPSecAuthAlgorithm authAlgorithm;

    @JsonProperty("encryption_algorithm")
    @ZoomField(name = "encryption_algorithm")
    public IPSecEncryptionAlgorithm encryptionAlgorithm;

    @JsonProperty("phase1_negotiation_mode")
    @ZoomField(name = "phase1_negotiation_mode")
    public Phase1NegotiationMode phase1NegMode;

    @JsonProperty("ike_version")
    @ZoomField(name = "ike_version")
    public IkeVersion ikeVersion;

    @JsonProperty("lifetime_units")
    @ZoomField(name = "lifetime_units")
    public String lifetimeUnits;

    @JsonProperty("lifetime_value")
    @ZoomField(name = "lifetime_value")
    public Integer lifetimeValue;

    @ZoomField(name = "pfs")
    public IPSecPfs pfs;

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("tenantId", tenantId)
            .add("name", name)
            .add("description", description)
            .add("authAlgorithm", authAlgorithm)
            .add("encryptionAlgorithm", encryptionAlgorithm)
            .add("phase1NegMode", phase1NegMode)
            .add("ikeVersion", ikeVersion)
            .add("lifetimeUnits", lifetimeUnits)
            .add("lifetimeValue", lifetimeValue)
            .add("pfs", pfs)
            .toString();
    }
}
