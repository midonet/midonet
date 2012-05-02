/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.ssh.builders;

import java.io.IOException;

import com.midokura.util.ssh.commands.CopyFileSshCommand;
import com.midokura.util.ssh.commands.SshSession;

/**
 * Command builder for a download file executed across a ssh session.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/27/12
 */
public class DownloadFileBuilder {
    private String remoteFile;
    private String localFile;

    public DownloadFileBuilder(String remoteFile) {
        this.remoteFile = remoteFile;
    }

    public DownloadFileBuilder fromRemote(String localFileName) {
        this.localFile = localFileName;
        return this;
    }

    public void usingSession(SshSession session) throws IOException {
        usingSession(session, 0);
    }

    public void usingSession(SshSession sshSession, int timeout)
        throws IOException {

        CopyFileSshCommand copyFileCommand = new CopyFileSshCommand(sshSession);

        copyFileCommand.doCopy(localFile, remoteFile, false, timeout);
    }
}
