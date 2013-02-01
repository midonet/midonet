/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package org.midonet.api.host;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.cluster.data.host.ErrorLogItem;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.List;
import java.util.UUID;

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

    public HostCommand(UUID hostId,
                       org.midonet.cluster.data.host.Command command) {
        this.id = command.getId();
        this.hostId = hostId;
        this.interfaceName = command.getInterfaceName();
        this.setCommands(translateCommands(command.getCommandList()));
        if (command.getErrorLogItem() != null) {
            this.setLogEntries(translateErrorLog(command.getErrorLogItem()));
        }
    }

    private HostCommand.Command[] translateCommands(
            List<HostDirectory.Command.AtomicCommand> list)
    {
        HostCommand.Command[] commands = new HostCommand.Command[list.size()];

        int pos = 0;
        for (HostDirectory.Command.AtomicCommand atomicCommand : list) {
            HostCommand.Command newCommand = new HostCommand.Command();

            newCommand.setOperation(atomicCommand.getOpType().name());
            newCommand.setProperty(atomicCommand.getProperty().getKey());
            newCommand.setValue(atomicCommand.getValue());
            commands[pos] = newCommand;

            pos = pos + 1;
        }

        return commands;
    }

    private HostCommand.LogEntry[] translateErrorLog(ErrorLogItem item) {

        HostCommand.LogEntry logEntry = new HostCommand.LogEntry();

        logEntry.setTimestamp(item.getTime().getTime());
        logEntry.setMessage(item.getError());

        return new HostCommand.LogEntry[] { logEntry };
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
