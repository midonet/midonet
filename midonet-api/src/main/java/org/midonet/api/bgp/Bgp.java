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
package org.midonet.api.bgp;

import org.midonet.api.UriResource;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.bgp.validation.IsUniqueBgpInPort;
import org.midonet.cluster.data.BGP;
import org.midonet.packets.IPv4Addr;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI; import java.util.UUID;

/**
 * Class representing BGP.
 */
@IsUniqueBgpInPort
@XmlRootElement
public class Bgp extends UriResource {

    private UUID id = null;
    private int localAS;
    private String peerAddr = null;
    private int peerAS;
    private UUID portId = null;

    /**
     * Default constructor
     */
    public Bgp() {
    }

    /**
     * Constructor
     *
     * @param data BGP data object.
     */
    public Bgp(BGP data) {
        this(data.getId(), data.getLocalAS(),
                data.getPeerAddr().toString(),
                data.getPeerAS(), data.getPortId());
    }

    /**
     * Constructor
     *
     * @param id ID of BGP
     * @param localAS Local AS number
     * @param peerAddr Peer IP address
     * @param peerAS Peer AS number
     * @param portId Port ID
     */
    public Bgp(UUID id, int localAS, String peerAddr, int peerAS, UUID portId) {
        this.id = id;
        this.localAS = localAS;
        this.peerAddr = peerAddr;
        this.peerAS = peerAS;
        this.portId = portId;
    }

    /**
     * Get BGP ID.
     *
     * @return BGP ID.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Set BGP ID.
     *
     * @param id
     *            ID of the BGP.
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * Get BGP localAS.
     *
     * @return BGP localAS.
     */
    public int getLocalAS() {
        return localAS;
    }

    /**
     * Set BGP localAS.
     *
     * @param localAS localAS of the BGP.
     */
    public void setLocalAS(int localAS) {
        this.localAS = localAS;
    }

    /**
     * Get peer address.
     *
     * @return peer address.
     */
    public String getPeerAddr() {
        return peerAddr;
    }

    /**
     * Set peer address.
     *
     * @param peerAddr Address of the peer.
     */
    public void setPeerAddr(String peerAddr) {
        this.peerAddr = peerAddr;
    }

    /**
     * Get BGP peerAS.
     *
     * @return BGP peerAS.
     */
    public int getPeerAS() {
        return peerAS;
    }

    /**
     * Set BGP peerAS.
     *
     * @param peerAS of the BGP.
     */
    public void setPeerAS(int peerAS) {
        this.peerAS = peerAS;
    }

    /**
     * Get port ID.
     *
     * @return Port ID.
     */
    public UUID getPortId() {
        return portId;
    }

    /**
     * Set port ID.
     *
     * @param portId Port ID of the BGP.
     */
    public void setPortId(UUID portId) {
        this.portId = portId;
    }

    /**
     * @return the port URI
     */
    public URI getPort() {
        if (getBaseUri() != null && portId != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), portId);
        }
        return null;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getBgp(getBaseUri(), id);
        }
        return null;
    }

    /**
     * @return the Ad routes URI
     */
    public URI getAdRoutes() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getBgpAdRoutes(getBaseUri(), id);
        }
        return null;
    }

    public BGP toData() {
        return new BGP()
                .setId(this.id)
                .setPortId(this.portId)
                .setLocalAS(this.localAS)
                .setPeerAddr(IPv4Addr.fromString(this.peerAddr))
                .setPeerAS(this.getPeerAS());
    }

    @Override
    public String toString() {
        return "id=" + id + ", localAS=" + localAS + ", peerAddr=" + peerAddr
                + ", peerAS=" + peerAS + ", portId=" + portId;
    }

}
