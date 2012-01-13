/*
* Copyright 2011 Midokura Europe SARL
*/
package com.midokura.tools.ssh.jsch;

import com.jcraft.jsch.*;
import com.midokura.tools.ssh.SshCommandExecutionFailedException;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/24/11
 * Time: 5:42 PM
 */
public class JschRemoteCommand extends JschCommand {

    public JschRemoteCommand(String username, String hostname, int port,
                             UserInfo userInfo) {
        super(username, hostname, port, userInfo);
    }

    public String execute(String command, int timeout) {

        try {
            Session session = getJschSession(timeout);

            ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
            channelExec.setCommand(command);
            channelExec.connect();

            InputStream remoteInputStream = channelExec.getInputStream();

            return IOUtils.toString(remoteInputStream, "UTF-8");

        } catch (Exception e) {
            throw new SshCommandExecutionFailedException(
                String.format("Execution failed for command [%s] on host %s : %s",
                              command, getHostConnectionString(), e.getMessage()), e);
        }
    }
}
