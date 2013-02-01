/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.util.ssh;

import com.midokura.util.ssh.builders.DownloadFileBuilder;
import com.midokura.util.ssh.builders.RemoteCommandBuilder;
import com.midokura.util.ssh.builders.RemoteProcessBuilder;
import com.midokura.util.ssh.builders.SshSessionBuilder;
import com.midokura.util.ssh.builders.UploadFileBuilder;

/**
 * @author Mihai Claudiu Toader <mtoader@gmail.com>
 *         Date: 11/24/11
 */
public class SshHelper {

    public static SshSessionBuilder newSession() {
        return new SshSessionBuilder() {
            @Override
            protected SshSessionBuilder self() {
                return this;
            }
        };
    }

    public static RemoteCommandBuilder newRemoteCommand(final String command) {
        return new RemoteCommandBuilder(command);
    }

    public static UploadFileBuilder uploadFile(String fileName) {
        return new UploadFileBuilder(fileName);
    }

    public static DownloadFileBuilder getFile(String fileName) {
        return new DownloadFileBuilder(fileName);
    }

    public static RemoteProcessBuilder newRemoteProcess(final String command) {
        return new RemoteProcessBuilder(command);
    }

    public interface SshOpBuilder<Builder extends SshOpBuilder<Builder>> {

        Builder onHost(String hostname);

        Builder onHost(String hostname, int port);

        Builder withCredentials(String user, String password);
    }
}
