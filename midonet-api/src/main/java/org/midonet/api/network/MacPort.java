/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
import org.midonet.packets.MAC;
import org.midonet.util.version.Since;

@MacPortValid(groups = MacPort.MacPortExtended.class)
@XmlRootElement
public class MacPort  extends RelativeUriResource {
    @NotNull
    @Pattern(regexp = MAC.regex)
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
