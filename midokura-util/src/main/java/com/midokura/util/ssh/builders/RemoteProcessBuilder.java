/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.ssh.builders;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.ssh.SshHelper;
import com.midokura.util.ssh.commands.SshExecChannel;
import com.midokura.util.ssh.commands.SshSession;

/**
 * This is a simple builder that collects parameters for a remote process
 * specification: user/pass, host/port, commandline.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/30/12
 */
public class RemoteProcessBuilder
    extends SshSessionBuilder<RemoteProcessBuilder>
    implements SshHelper.SshOpBuilder<RemoteProcessBuilder> {

    private static final Logger log = LoggerFactory
        .getLogger(RemoteProcessBuilder.class);

    private String command;
    private Map<String, String> envVars = null;

    public RemoteProcessBuilder(String command) {
        this.command = command;
    }

    public RemoteProcessBuilder setEnvVars(Map<String, String> envVars) {
        this.envVars = envVars;
        return this;
    }

    @Override
    protected RemoteProcessBuilder self() {
        return this;
    }

    /**
     * This will execute the process remotely but it will wait indefinitely
     * for remote connection to be established.
     *
     * @return an communication channel with the remote process
     */
    public SshExecChannel execute() throws IOException {
        return execute(0);
    }

    /**
     * This will execute the process remotely with a connection timeout in
     * milliseconds. Passing 0 to the timeout will cause the connection to wait
     * establishment indefinitely.
     *
     * @param timeout the time in milliseconds that the connection is attempted
     * @return an communication channel with the remote process
     */
    public SshExecChannel execute(int timeout)
        throws IOException {
        SshSession session = open(timeout);

        SshExecChannel execChannel = session.newExecChannel();

        execChannel.setCommand(command);
        execChannel.setPty(true);
        execChannel.connect(timeout);
        if (envVars != null) {
            execChannel.setEnvVariables(envVars);
        }

        return execChannel;
    }
}
