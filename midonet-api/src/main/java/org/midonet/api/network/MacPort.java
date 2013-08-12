/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.api.network;

import java.net.URI;
import java.util.UUID;
import javax.validation.GroupSequence;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.api.RelativeUriResource;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.network.validation.MacPortValid;
import org.midonet.util.StringUtil;
import org.midonet.util.version.Since;

@MacPortValid(groups = MacPort.MacPortExtended.class)
@XmlRootElement
public class MacPort  extends RelativeUriResource {
    @NotNull
    @Pattern(regexp = StringUtil.MAC_ADDRESS_REGEX_PATTERN)
    protected String macAddr;

    @NotNull
    protected UUID portId;

    @Since("2")
    protected Short vlanId;

    // This is only needed for validating that the port belongs to the bridge.
    protected UUID bridgeId;

    public MacPort(String macAddr, UUID portId) {
        this.macAddr = macAddr;
        this.portId = portId;
    }

    public MacPort(Short vlanId, String macAddr, UUID portId) {
        this(macAddr, portId);
        this.vlanId = vlanId;
    }

    /* Default constructor - for deserialization. */
    public MacPort() {
    }

    @Override
    public URI getUri() {
        if (getParentUri() != null && macAddr != null) {
            return ResourceUriBuilder.getMacPort(getParentUri(), this);
        } else {
            return null;
        }
    }

    public String getMacAddr() {
        return macAddr;
    }

    public void setMacAddr(String macAddr) {
        this.macAddr = macAddr;
    }

    public UUID getPortId() {
        return portId;
    }

    public void setPortId(UUID portId) {
        this.portId = portId;
    }

    public Short getVlanId() {
        return vlanId;
    }

    public void setVlanId(Short vlanId) {
        this.vlanId = vlanId;
    }

    public UUID getBridgeId() {
        return bridgeId;
    }

    public void setBridgeId(UUID bridgeId) {
        this.bridgeId = bridgeId;
    }

    /**
     * Interface used for a Validation group. This group gets triggered after
     * the default validations.
     */
    public interface MacPortExtended {
    }

    /**
     * Interface that defines the ordering of validation groups.
     */
    @GroupSequence({ Default.class, MacPortExtended.class })
    public interface MacPortGroupSequence {
    }
}
