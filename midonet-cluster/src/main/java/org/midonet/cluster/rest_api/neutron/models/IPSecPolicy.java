package org.midonet.cluster.rest_api.neutron.models;

import java.util.List;
import java.util.UUID;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;
import org.midonet.cluster.util.UUIDUtil;

@ZoomClass(clazz = Neutron.IPSecPolicy.class)
public class IPSecPolicy extends ZoomObject {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "description")
    public String description;

    @ZoomField(name = "transform_protocol")
    public TransformProtocol transformProtocol;

    @ZoomField(name = "auth_algorithm")
    public IPSecAuthAlgorithm authAlgorithm;

    @ZoomField(name = "encryption_algorithm")
    public IPSecEncryptionAlgorithm encryptionAlgorithm;

    @ZoomField(name = "encapsulation_mode")
    public EncapsulationMode encapsulationMode;

    @ZoomField(name = "pfs")
    public IPSecPFS pfs;

    @ZoomField(name = "lifetime")
    public List<String> lifetime; // [(units:value)]*

    @ZoomEnum(clazz = Neutron.IPSecPolicy.TransformProtocol.class)
    public enum TransformProtocol {
        @ZoomEnumValue("ESP") ESP,
        @ZoomEnumValue("AH") AH,
        @ZoomEnumValue("AH_ESP") AH_ESP
    }

    @ZoomEnum(clazz = Neutron.IPSecPolicy.EncapsulationMode.class)
    public enum EncapsulationMode {
        @ZoomEnumValue("TUNNEL") TUNNEL,
        @ZoomEnumValue("TRANSPORT") TRANSPORT
    }
}
