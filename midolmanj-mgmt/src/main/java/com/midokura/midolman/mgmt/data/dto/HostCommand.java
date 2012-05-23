/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;

/**
 * Class encapsulating the commands that should be executed by a node agent into
 * a host at startup.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com> Date: 2/20/12
 */
@XmlRootElement
public class HostCommand extends UriResource {
    Integer id;
    UUID hostId;

    public HostCommand() {
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public UUID getHostId() {
        return hostId;
    }

    public void setHostId(UUID hostId) {
        this.hostId = hostId;
    }

    @Override
    public URI getUri() {
        if (super.getBaseUri() != null && hostId != null) {
            return ResourceUriBuilder.getHostCommand(super.getBaseUri(),
                    hostId, id);
        } else {
            return null;
        }
    }
}
