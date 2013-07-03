/**
 * Copyright 2013 Midokura Pte Ltd.
 */
package org.midonet.api.tracing;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;
import org.midonet.api.filter.Condition;
import org.midonet.api.ResourceUriBuilder;

/* Trace condition class */
@XmlRootElement
public class TraceCondition extends Condition {

    private UUID id;

    public TraceCondition() { super(); }

    public TraceCondition(org.midonet.cluster.data.TraceCondition data) {
        this.id = UUID.fromString(data.getId().toString());
        setFromCondition(data.getCondition());
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public org.midonet.cluster.data.TraceCondition toData() {
        return new org.midonet.cluster.data.TraceCondition(id, makeCondition());
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getTraceCondition(getBaseUri(), id);
        } else {
            return null;
        }
    }
}
