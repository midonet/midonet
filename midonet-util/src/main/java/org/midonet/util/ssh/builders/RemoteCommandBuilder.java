/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.util.ssh.builders;

import java.io.IOException;

import org.midonet.util.ssh.SshHelper;
import org.midonet.util.ssh.commands.RemoteSshCommand;

/**
 * Command builder for a shell command executed via a ssh connection.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/30/12
 */
public class RemoteCommandBuilder
    extends SshSessionBuilder<RemoteCommandBuilder>
    implements SshHelper.SshOpBuilder<RemoteCommandBuilder> {

    private final String command;

    public RemoteCommandBuilder(String command) {
        this.command = command;
    }

    @Override
    protected RemoteCommandBuilder self() {
        return this;
    }

    public String run() throws IOException {
        return run(0);
    }

    public String run(int timeout) throws IOException {
        return
            new RemoteSshCommand(open(timeout))
                .execute(command, timeout);
    }
}
