/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.client.dto;

import java.net.URI;
import java.util.UUID;

public interface DtoLogicalPort {

    /**
     * @return Peer port ID
     */
    UUID getPeerId();

    /**
     * @param peerId peer ID to set
     */
    void setPeerId(UUID peerId);

    /**
     * @return URI of the peer
     */
    URI getPeer();

    /**
     * @param peer URI to set
     */
    void setPeer(URI peer);

    /**
     * @return URI to link
     */
    URI getLink();

    /**
     * @param link URI to set
     */
    void setLink(URI link);
}
