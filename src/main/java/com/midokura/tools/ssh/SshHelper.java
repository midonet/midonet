/*
* Copyright 2011 Midokura Europe SARL
*/
package com.midokura.tools.ssh;

import com.midokura.tools.ssh.jsch.JschCopyFileCommand;
import com.midokura.tools.ssh.jsch.JschRemoteCommand;
import com.midokura.tools.ssh.jsch.PasswordCredentialsUserInfo;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/24/11
 * Time: 5:34 PM
 */
public class SshHelper {
    
    public static SendFileCommandBuilder copyFileTo(final String localFileName,
                                                    final String remoteFileName)
    {
        return new ScpCommandBuilder(localFileName, remoteFileName) {

            @Override
            public boolean runWithTimeout(int timeout) {
                return executeCopy(true, timeout);
            }
        };
    }
    
    public static SendFileCommandBuilder copyFileFrom(String localFileName,
                                                      String remoteFileName)
    {
        return new ScpCommandBuilder(localFileName, remoteFileName) {
            @Override
            public boolean runWithTimeout(int timeout) {
                return executeCopy(false, timeout);
            }
        };
    }

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
                        new PasswordCredentialsUserInfo(this.password))
                    .execute(command, timeout);
            }
        };
    }

    public interface SendFileCommandBuilder {

        SendFileCommandBuilder onHost(String hostname);

        SendFileCommandBuilder onHost(String hostname, int port);

        SendFileCommandBuilder withCredentials(String user, String password);

        boolean run();

        boolean runWithTimeout(int timeout);
    }
    
    public interface RemoteCommandBuilder {

        RemoteCommandBuilder onHost(String hostname);

        RemoteCommandBuilder onHost(String hostname, int port);

        RemoteCommandBuilder withCredentials(String user, String password);

        String run();

        String runWithTimeout(int timeout);
    }

    private abstract static class ScpCommandBuilder implements SendFileCommandBuilder {
        String hostname;
        String username;
        String password;
        int port;
        private final String localFileName;
        private final String remoteFileName;

        public ScpCommandBuilder(String localFileName, String remoteFileName) {
            this.localFileName = localFileName;
            this.remoteFileName = remoteFileName;
            port = 22;
        }

        @Override
        public SendFileCommandBuilder onHost(String hostname) {
            return onHost(hostname, 22);
        }

        @Override
        public SendFileCommandBuilder onHost(String hostname, int port) {
            this.hostname = hostname;
            this.port = port;
            return this;
        }

        @Override
        public SendFileCommandBuilder withCredentials(String username,
                                                      String password) {
            this.username = username;
            this.password = password;
            return this;
        }

        @Override
        public boolean run() {
            return runWithTimeout(-1);
        }

        @Override
        public abstract boolean runWithTimeout(int timeout);

        protected boolean executeCopy(boolean mode, int timeout) {
            return new JschCopyFileCommand(
                username, hostname, port,
                new PasswordCredentialsUserInfo(this.password))
                .doCopy(localFileName, remoteFileName, mode, timeout);
        }
    }
}
