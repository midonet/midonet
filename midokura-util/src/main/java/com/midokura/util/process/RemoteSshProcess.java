package com.midokura.util.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.remote.RemoteHost;
import com.midokura.util.ssh.SshHelper;
import com.midokura.util.ssh.commands.SshExecChannel;

/**
 * A Process class that abstracts a process executed across a ssh session.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 3/30/12
 */
public class RemoteSshProcess extends Process {

    private static final Logger log = LoggerFactory
        .getLogger(RemoteSshProcess.class);

    SshExecChannel sshExecChannel;
    String command;

    public RemoteSshProcess(RemoteHost host, String commandLine,
                            Map<String, String> envVars)
        throws IOException {
        this.command = commandLine;

        String vars = "NONE";
        if (envVars != null && !envVars.isEmpty())
            vars = envVars.toString();
        log.info("Launching command \"{}\" on remote host {} with env vars {} ",
                 new Object[]{command, host.getSafeName(), vars});

        sshExecChannel =
            SshHelper.newRemoteProcess(command)
                     .withSession(host.getSession())
                     .setEnvVars(envVars)
                     .execute();

        log.debug("Command launched successfully");
    }

    public RemoteSshProcess(RemoteHost host, String commandLine)
        throws IOException {

        new RemoteSshProcess(host, commandLine, null);
    }

    @Override

    public OutputStream getOutputStream() {
        try {
            return sshExecChannel.getOutputStream();
        } catch (IOException e) {
            log.error(
                "Exception while retrieving an execution channel's OutputStream",
                e);
        }

        return null;
    }


    @Override
    public InputStream getInputStream() {
        try {
            return sshExecChannel.getInputStream();
        } catch (IOException e) {
            log.error(
                "Exception while retrieving an ssh exec channel's InputStream",
                e);
        }

        return null;
    }


    @Override
    public InputStream getErrorStream() {
        try {
            return sshExecChannel.getErrStream();
        } catch (IOException e) {
            log.error(
                "Exception while retrieving an execution channel's ErrorStream",
                e);
        }

        return null;
    }

    @Override
    public int waitFor() throws InterruptedException {
        while (!sshExecChannel.isClosed()) {
            log.trace("Waiting for remote process \"{}\"to close.", command);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                //
            }
        }

        return sshExecChannel.getExitStatus();
    }

    @Override
    public int exitValue() {
        try {
            return sshExecChannel.getExitStatus();
        } catch (Exception e) {
            log.error(
                "Exception while retrieving execution channel's exit code",
                e);
        }

        return -1;
    }

    @Override
    public void destroy() {
        try {
            sshExecChannel.disconnect();
        } catch (Exception e) {
            log.error("Exception while closing the remote execution channel.",
                      e);
        }
    }
}
