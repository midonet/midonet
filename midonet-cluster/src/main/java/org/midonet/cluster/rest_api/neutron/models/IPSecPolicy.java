package org.midonet.cluster.rest_api.neutron.models;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;

@ZoomClass(clazz = Neutron.IPSecPolicy.class)
public class IPSecPolicy extends ZoomObject {

    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "description")
    public String description;

    @JsonProperty("transform_protocol")
    @ZoomField(name = "transform_protocol")
    public TransformProtocol transformProtocol;

    @JsonProperty("auth_algorithm")
    @ZoomField(name = "auth_algorithm")
    public IPSecAuthAlgorithm authAlgorithm;

    @JsonProperty("encryption_algorithm")
    @ZoomField(name = "encryption_algorithm")
    public IPSecEncryptionAlgorithm encryptionAlgorithm;

    @JsonProperty("encapsulation_mode")
    @ZoomField(name = "encapsulation_mode")
    public EncapsulationMode encapsulationMode;

    @ZoomField(name = "pfs")
    public IPSecPFS pfs;

    @JsonProperty("lifetime_units")
    @ZoomField(name = "lifetime_units")
    public String lifetimeUnits;

    @JsonProperty("lifetime_value")
    @ZoomField(name = "lifetime_value")
    public Integer lifetimeValue;

    @ZoomEnum(clazz = Neutron.IPSecPolicy.TransformProtocol.class)
    public enum TransformProtocol {
        @ZoomEnumValue("ESP") ESP,
        @ZoomEnumValue("AH") AH,
        @ZoomEnumValue("AH_ESP") AH_ESP;

        @JsonCreator
        @SuppressWarnings("unused")
        public static TransformProtocol forValue(String v) {
            return valueOf(IPSecSiteConnection.convertFromIpsecString(v));
        }
    }

    @ZoomEnum(clazz = Neutron.IPSecPolicy.EncapsulationMode.class)
    public enum EncapsulationMode {
        @ZoomEnumValue("TUNNEL") TUNNEL,
        @ZoomEnumValue("TRANSPORT") TRANSPORT;

        @JsonCreator
        @SuppressWarnings("unused")
        public static EncapsulationMode forValue(String v) {
            return valueOf(IPSecSiteConnection.convertFromIpsecString(v));
        }
    }
}
