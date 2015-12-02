package org.midonet.cluster.rest_api.neutron.models;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Neutron;

@ZoomClass(clazz = org.midonet.cluster.models.Neutron.IKEPolicy.class)
public class IKEPolicy {

    // TODO: this field and the enum is pointless, leave out and add whenever
    // needed?
    @ZoomEnum(clazz = Neutron.IKEPolicy.Phase1NegotiationMode.class)
    public enum Phase1NegotiationMode {
        @ZoomEnumValue("MAIN") MAIN
    };

    @ZoomEnum(clazz = Neutron.IKEPolicy.IkeVersion.class)
    public enum IkeVersion {
        @ZoomEnumValue("V1") V1,
        @ZoomEnumValue("V2") V2
    };

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
    public IPSecPFS pfs;
}
