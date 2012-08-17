/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;

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
    String interfaceName;
    Command[] commands;
    LogEntry[] logEntries;

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

    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public Command[] getCommands() {
        return commands;
    }

    public void setCommands(Command[] commands) {
        this.commands = commands;
    }

    public LogEntry[] getLogEntries() {
        return logEntries;
    }

    public void setLogEntries(LogEntry[] logEntries) {
        this.logEntries = logEntries;
    }

    public static class LogEntry {

        long timestamp;
        String message;

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class Command {

        String operation;
        String property;
        String value;

        public String getOperation() {
            return operation;
        }

        public void setOperation(String operation) {
            this.operation = operation;
        }

        public String getProperty() {
            return property;
        }

        public void setProperty(String property) {
            this.property = property;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

}
