/*
 * Copyright (c) 2013 Midokura Pte.Ltd.
 */

package org.midonet.client.dto;

import java.util.List;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * DtoTrace represent a query to the Packet Trace system.
 */

@XmlRootElement
public class DtoTrace {

    /*
     * trace ID
     */
    UUID traceId;

    /*
     * number of trace messages
     */
    int numTraceMessages;

    /*
     * the trace message
     */
    List<String> traceMessages;

    public UUID getTraceId() {
        return traceId;
    }

    public int getNumTraceMessages() {
        return numTraceMessages;
    }

    public List<String> getTraceMessages() {
        return traceMessages;
    }

    public void setTraceId(UUID traceId) {
        this.traceId = traceId;
    }

    public void setNumTraceMessages(int numTraceMessages) {
        this.numTraceMessages = numTraceMessages;
    }

    public void setTraceMessages(List<String> traceMessages) {
        this.traceMessages = traceMessages;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("traceMessages=").append(traceMessages);
        return "DtoTrace{" +
                "traceId=" + traceId.toString() +
                " numTraceMessages=" + numTraceMessages +
                " " + sb.toString() +
                "}";
    }
}
