/*
* Copyright 2011 Midokura Europe SARL
*/
package com.midokura.util.ssh.commands;

import java.io.IOException;
import java.io.InputStream;

import com.jcraft.jsch.UserInfo;
import org.apache.commons.io.IOUtils;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 11/24/11
 */
public class RemoteSshCommand extends SshSession {

    public RemoteSshCommand(String username,
                            String hostname, int port,
                            UserInfo userInfo) {
        super(username, hostname, port, userInfo);
    }

    protected void customizeChannel(SshExecChannel channel) {

    }

    public String execute(String command, int timeout) throws IOException {

        openSession(timeout);

        SshExecChannel channel = newExecChannel();

        channel.setCommand(command);
        customizeChannel(channel);
        channel.connect(timeout);

        InputStream remoteInputStream = channel.getInputStream();

        return IOUtils.toString(remoteInputStream, "UTF-8");
    }
}
