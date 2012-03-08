/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.command;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.state.HostDirectory;
import com.midokura.midolman.agent.state.HostZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;

public class NodeCommandWatcher {

    private final static Logger log = LoggerFactory.getLogger(
            NodeCommandWatcher.class);

    private static CommandInterpreter cmdInterpreter;
    private UUID hostId;
    protected Set<UUID> executedCommands;
    private CommanderWatcher watcher;

    @Inject
    HostZkManager zkManager;

    public void checkCommands(UUID hostId) {
        this.hostId = hostId;
        this.watcher = new CommanderWatcher();
        this.executedCommands = new HashSet<UUID>();
        updateCommand();
    }

    private class CommanderWatcher implements Runnable {
        @Override
        public void run() {
            try {
                updateCommand();
            } catch (Exception e) {
                log.warn("CommanderWatcher.run", e);
            }
        }
    }

    private void updateCommand() {
        Collection<ZkNodeEntry<UUID, HostDirectory.Command>> entryList = null;
        try {
            entryList = zkManager.list(hostId, watcher);
        } catch (StateAccessException e) {
            log.warn("Cannot list the Commands.", e);
            return;
        }
        Map<UUID, HostDirectory.Command> currentCommands = new HashMap<UUID, HostDirectory.Command>();
        for (ZkNodeEntry<UUID, HostDirectory.Command> entry : entryList) {
            currentCommands.put(entry.key, entry.value);
        }
        Set<UUID> newCommands = new HashSet<UUID>(currentCommands.keySet());
        newCommands.removeAll(executedCommands);

        // Any brand new command should be processed.
        for (UUID command : newCommands) {
            //TODO(rossella) thread pool? here or in executeCommands?
            executeCommands(currentCommands.get(command));
            // add it to executed
            executedCommands.add(command);
        }
    }

    private void executeCommands(HostDirectory.Command cmd) {
        CommandInterpreter interpreter = new CommandInterpreter();
        CommandExecutor[] commandToExecute;
        try {
            commandToExecute = interpreter.interpret(cmd);
        } catch (Exception e) {
            log.error("Execute commands: ", e);
            return;
        }
        // TODO(rossella) thread pool??
        for (CommandExecutor cmdEx : commandToExecute) {
            cmdEx.execute();
        }
    }
}
