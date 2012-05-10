/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.ssh.builders;

import java.io.IOException;

import com.midokura.util.ssh.commands.RemoteSshCommand;
import com.midokura.util.ssh.SshHelper;
import com.midokura.util.ssh.jsch.PasswordCredentialsUserInfo;

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
            new RemoteSshCommand(
                username, hostname, port,
                new PasswordCredentialsUserInfo(this.password))
                .execute(command, timeout);
    }
}
