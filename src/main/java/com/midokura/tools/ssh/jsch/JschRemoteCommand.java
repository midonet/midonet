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
public class JschRemoteCommand {

    String username;
    String hostname;
    int port;
    UserInfo userInfo;

    public JschRemoteCommand(String username, String hostname, int port, UserInfo userInfo) {
        this.username = username;
        this.hostname = hostname;
        this.port = port;
        this.userInfo = userInfo;
    }

    public String execute(String command, int timeout) {
        JSch jsch = new JSch();
        Session session = null;
        try {
            session = jsch.getSession(username, hostname, port);

            session.setUserInfo(userInfo);

            if (timeout == -1) {
                session.connect();
            } else {
                session.connect(timeout);
            }

            ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
            channelExec.setCommand(command);
            channelExec.connect();

            InputStream remoteInputStream = channelExec.getInputStream();

            return IOUtils.toString(remoteInputStream, "UTF-8");

        } catch (JSchException e) {
            throw new SshCommandExecutionFailedException(String.format("Execution failed for command [%s] on host %s@%s:%d", command, username, hostname, port), e);
        } catch (IOException e) {
            throw new SshCommandExecutionFailedException(String.format("Execution failed for command [%s] on host %s@%s:%d", command, username, hostname, port), e);
        }
    }
}
