/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.util.ssh.commands;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;

/**
 * An abstraction of a ssh execution channel. It's created by the SshHelper and
 * can provide access to a process Input/Output/Err Stream, exit status, etc.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/27/12
 */
public class SshExecChannel {

    private final ChannelExec channel;
    private final SshSession sshSession;

    public SshExecChannel(ChannelExec channel, SshSession sshSession) {
        this.channel = channel;
        this.sshSession = sshSession;
    }

    public void setEnvVariables(Map<String, String> vars) {
        for (Map.Entry<String, String> var : vars.entrySet()) {
            channel.setEnv(var.getKey(), var.getValue());
        }
    }

    public void setCommand(String command) {
        channel.setCommand(command);
    }

    public void connect() throws IOException {
        connect(0);
    }

    public void connect(int timeout) throws IOException {
        try {
            channel.connect(timeout);
        } catch (JSchException e) {
            throw new IOException(
                "Exception connecting the exec channel on host: " +
                    sshSession.getHostConnectionString(),
                e);
        }
    }

    public InputStream getInputStream() throws IOException {
        return channel.getInputStream();
    }

    public OutputStream getOutputStream() throws IOException {
        return channel.getOutputStream();
    }

    public InputStream getErrStream() throws IOException {
        return channel.getErrStream();
    }

    public int checkAck(InputStream in) throws IOException {
        int b = in.read();
        // b may be 0 for success,
        //          1 for error,
        //          2 for fatal error,
        //         -1 EOF (from the underling InputStream)

        if (b == 0) return b;
        if (b == -1) return b;

        if (b == 1 || b == 2) {
            int c;

            do {
                c = in.read();
            } while (c != '\n');
        }

        return b;
    }

    public void sendAck(OutputStream stream) throws IOException {
        stream.write('\0');
        stream.flush();
    }

    public void sendString(OutputStream stream, String data)
        throws IOException {
        stream.write(data.getBytes());
        stream.flush();
    }

    public void disconnect() {
        channel.disconnect();
    }

    public void setPty(boolean enable) {
        channel.setPty(enable);
    }

    public boolean isClosed() {
        return channel.isClosed();
    }

    public int getExitStatus() {
        return channel.getExitStatus();
    }
}
