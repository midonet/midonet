/*
 * Copyright 2013 Midokura Pte Ltd.
 */
package org.midonet.api.tracing;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.util.StringUtil;

/* Packet Trace class */
@XmlRootElement
public class Trace extends UriResource {

    private UUID traceId;

    int numTraceMessage;

    List<String> traceMessages;

    public Trace() {
        super();
    }

    public UUID getTraceId() {
        return traceId;
    }

    public void setTraceId(UUID traceId) {
        this.traceId = traceId;
    }

    public int getNumTraceMessages() {
        return numTraceMessage;
    }

    public void setNumTraceMessages(int numTraceMessage) {
        this.numTraceMessage = numTraceMessage;
    }

    public List<String> getTraceMessages() {
        return traceMessages;
    }

    public void setTraceMessages(List<String> traceMessages) {
        this.traceMessages = traceMessages;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && traceId != null) {
            return ResourceUriBuilder.getTrace(getBaseUri(), traceId);
        } else {
            return null;
        }
    }
}
