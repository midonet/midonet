/*
* Copyright 2011 Midokura Europe SARL
*/
package com.midokura.tools.ssh;

import com.midokura.tools.ssh.jsch.JschRemoteCommand;
import com.midokura.tools.ssh.jsch.PasswordCredentialsUserInfo;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/24/11
 * Time: 5:34 PM
 */
public class SshHelper {
    public static RemoteCommandBuilder newRemoteCommand(final String command) {
        return new RemoteCommandBuilder() {

            String hostname = "localhost";
            String username;
            String password;
            int port = 22;
            long timeout = -1;

            @Override
            public RemoteCommandBuilder onHost(String hostname) {
                return onHost(hostname, 22);
            }

            @Override
            public RemoteCommandBuilder onHost(String hostname, int port) {
                this.hostname = hostname;
                return this;
            }

            @Override
            public RemoteCommandBuilder withCredentials(String username, String password) {
                this.username = username;
                this.password = password;
                return this;
            }

            @Override
            public String run() {
                return runWithTimeout(-1);
            }

            @Override
            public String runWithTimeout(int timeout) {
                this.timeout = timeout;

                return new JschRemoteCommand(
                        username, hostname, port,
                        new PasswordCredentialsUserInfo(this.password)).execute(command, timeout);
            }
        };
    }

    public interface RemoteCommandBuilder {

        RemoteCommandBuilder onHost(String hostname);

        RemoteCommandBuilder onHost(String hostname, int port);

        RemoteCommandBuilder withCredentials(String user, String password);

        String run();

        String runWithTimeout(int timeout);
    }
}
