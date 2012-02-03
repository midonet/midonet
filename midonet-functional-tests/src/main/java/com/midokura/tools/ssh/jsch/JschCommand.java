/*
* Copyright 2011 Midokura Europe SARL
*/
package com.midokura.tools.ssh.jsch;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Author: Toader Mihai Claudiu <mtoader@midokura.com>
 * <p/>
 * Date: 12/14/11
 * Time: 3:03 PM
 */
public class JschCommand {

    private String username;
    private String hostname;
    private int port;
    private UserInfo userInfo;

    public JschCommand(String username, String hostname, int port,
                       UserInfo userInfo) {
        this.username = username;
        this.hostname = hostname;
        this.port = port;
        this.userInfo = userInfo;
    }

    protected Session getJschSession(int timeout) throws JSchException {
        JSch jsch = new JSch();
        Session session = null;
        session = jsch.getSession(username, hostname, port);

        session.setUserInfo(userInfo);

        if (timeout == -1) {
            session.connect();
        } else {
            session.connect(timeout);
        }

        return session;
    }

    protected String getHostConnectionString() {
        return String.format("%s@%s:%d", username, hostname, port);
    }

    protected void sendAck(OutputStream stream) throws IOException {
        stream.write('\0');
        stream.flush();
    }

    protected void sendString(OutputStream stream, String data) throws IOException {
        stream.write(data.getBytes());
        stream.flush();
    }

    protected int checkAck(InputStream in) throws IOException {

        int b = in.read();
        // b may be 0 for success,
        //          1 for error,
        //          2 for fatal error,
        //          -1

        if (b == 0) return b;
        if (b == -1) return b;

        if (b == 1 || b == 2) {
            StringBuilder messageBuilder = new StringBuilder();
            int c;

            do {
                c = in.read();
                messageBuilder.append((char) c);
            } while (c != '\n');

            System.out.print(messageBuilder.toString());
        }

        return b;
    }
}
