/*
* Copyright 2011 Midokura Europe SARL
*/
package com.midokura.util.ssh.commands;

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.IOUtils;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 11/24/11
 */
public class RemoteSshCommand {

    private SshSession sshSession;

    public RemoteSshCommand(SshSession sshSession) {
        this.sshSession = sshSession;
    }

    protected void customizeChannel(SshExecChannel channel) {

    }

    public String execute(String command, int timeout) throws IOException {

        sshSession.openSession(timeout);

        SshExecChannel channel = sshSession.newExecChannel();

        try {
            channel.setCommand(command);
            customizeChannel(channel);

            InputStream remoteInputStream = channel.getInputStream();
            channel.connect(timeout);

            return IOUtils.toString(remoteInputStream, "UTF-8");
        } finally {
            channel.disconnect();
        }
    }
}
