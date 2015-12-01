package org.midonet.cluster.rest_api.neutron.models;

import java.util.List;
import java.util.UUID;

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

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "description")
    public String description;

    @ZoomField(name = "auth_algorithm")
    public IPSecAuthAlgorithm authAlgorithm;

    @ZoomField(name = "encryption_algorithm")
    public IPSecEncryptionAlgorithm encryptionAlgorithm;

    @ZoomField(name = "encryption_algorithm")
    public Phase1NegotiationMode phase1NegMode;

    @ZoomField(name = "ikeVersion")
    public Integer ikeVersion;

    @ZoomField(name = "lifetime")
    public List<String> lifetime; // [(units:value)]*

    @ZoomField(name = "pfs")
    public IPSecPFS pfs;
}
