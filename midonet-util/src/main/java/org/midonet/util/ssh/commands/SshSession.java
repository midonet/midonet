/*
* Copyright 2011 Midokura Europe SARL
*/
package org.midonet.util.ssh.commands;

import java.io.IOException;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class that handles logic to create and maintain a Session to a remote ssh host.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 12/14/11
 */
public class SshSession {

    private static final Logger log = LoggerFactory.getLogger(SshSession.class);

    private String username;
    private String hostname;
    private int port;
    private UserInfo userInfo;

    private Session session;

    public SshSession(String username,
                      String hostname, int port, UserInfo userInfo) {
        this.username = username;
        this.hostname = hostname;
        this.port = port;
        this.userInfo = userInfo;
    }

    public void openSession() throws IOException {
        openSession(0);
    }

    public void openSession(int timeout) throws IOException {

        if (session == null) {
            try {
                JSch jsch = new JSch();
                session = jsch.getSession(username, hostname, port);

                session.setUserInfo(userInfo);
                session.connect(timeout);
            } catch (JSchException e) {
                throw
                    new IOException(
                        "Failed to open ssh session for: " +
                            getHostConnectionString(), e);
            }
        }
    }

    protected String getHostConnectionString() {
        return String.format("%s@%s:%d", username, hostname, port);
    }

    public SshExecChannel newExecChannel() throws IOException {
        if (session == null) {
            throw new IOException(String.format(
                "SshSession to host: %s is down. Can't open exec channel.",
                getHostConnectionString()));
        }

        ChannelExec exec;
        try {
            if (session == null) {
                openSession();
            }
            exec = (ChannelExec) session.openChannel("exec");
        } catch (JSchException e) {
            throw new IOException(
                "Failed to open ssh exec channel to host: " + getHostConnectionString(),
                e);
        }

        return new SshExecChannel(exec, this);
    }

    public void disconnect() {
        if (session != null) {
            session.disconnect();
        }
    }

    public void setLocalPortForward(int localPort) throws Exception {
        setLocalPortForward(localPort, "localhost", localPort);
    }

    public void setLocalPortForward(int localPort, String remoteHost)
        throws Exception {
        setLocalPortForward(localPort, remoteHost, localPort);
    }

    public void setLocalPortForward(int localPort,
                                    String remoteHost, int remotePort)
        throws Exception {

        session.setPortForwardingL(localPort, remoteHost, remotePort);
        log.debug("Forwarded local port {} to {}:{} via session: {}",
                  new Object[]{localPort,
                      remoteHost, remotePort,
                      getHostConnectionString()});
    }

    public void setRemotePortForward(int remotePort) throws Exception {
        setRemotePortForward(remotePort, "localhost", remotePort);
    }

    public void setRemotePortForward(int remotePort, String localHost)
        throws Exception {
        setRemotePortForward(remotePort, localHost, remotePort);
    }

    public void setRemotePortForward(int remotePort, String localHost,
                                     int localPort)
        throws Exception {

        session.setPortForwardingR(remotePort, localHost, localPort);
        log.debug("Forwarding remote port {} back to {}:{} via session {}.",
                  new Object[]{localPort,
                      localHost, remotePort,
                      getHostConnectionString()});
    }
}
