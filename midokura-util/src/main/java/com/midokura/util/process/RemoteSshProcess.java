package com.midokura.util.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.ssh.jsch.JschCommand;
import com.midokura.util.ssh.jsch.PasswordCredentialsUserInfo;

/**
 * // TODO: Explain yourself.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 3/30/12
 */
public class RemoteSshProcess extends Process {

    private static final Logger log = LoggerFactory
        .getLogger(RemoteSshProcess.class);

    private static Pattern targetHostSpecPattern =
        Pattern.compile("([^:]+)(?::([^@]+))@([^:]+)(?::(\\d+))");

    JschCommand remoteCommand;
    ChannelExec executionChannel;
    Session session;
    String command;


    public RemoteSshProcess(String targetHost, String command) {
        // user@host
        // user@host:port
        // user:pass@host:port

        String user = "";
        String pass = "";
        String host = "";
        int port = 0;

        Matcher m = targetHostSpecPattern.matcher(targetHost);
        if ( m.matches() ) {
            switch (m.groupCount()) {
                case 2:
                    user = m.group(1);
                    host = m.group(2);
                    break;
                case 4:
                    user = m.group(1);
                    pass = m.group(2);
                    host = m.group(3);
                    port = Integer.parseInt(m.group(4));
                    break;
                case 3:
                    user = m.group(1);

                    // temporarily assign the second to pass and the third to host
                    pass = m.group(2);
                    host = m.group(3);

                    // check to see if we need to shift the second and third parts
                    MatchResult matchResult = m.toMatchResult();

                    // if the next char after the first group is '@' we need to
                    // shift matched blocks
                    if ( targetHost.charAt(matchResult.end(1) + 1) == '@' ) {
                        pass = ""; // clean the old data
                        host = m.group(2);
                        port = Integer.parseInt(m.group());
                    }
                    break;
            }
        }

        remoteCommand = new JschCommand(user, host, port,
                                        new PasswordCredentialsUserInfo(
                                            pass));
        this.command = command;

        try {
            session = remoteCommand.getJschSession(-1);

            executionChannel = (ChannelExec) session.openChannel("exec");
            executionChannel.setCommand(command);
            executionChannel.setPty(true);
            executionChannel.connect();
        } catch (JSchException e) {
            log.error("Exception", e);
        }
    }

    @Override

    public OutputStream getOutputStream() {
        try {
            return executionChannel.getOutputStream();
        } catch (IOException e) {
            log.error("Exception", e);
        }

        return null;
    }


    @Override
    public InputStream getInputStream() {
        try {
            return executionChannel.getInputStream();
        } catch (IOException e) {
            log.error("Exception", e);
        }

        return null;
    }


    @Override
    public InputStream getErrorStream() {
        try {
            return executionChannel.getErrStream();
        } catch (IOException e) {
            log.error("Exception", e);
        }

        return null;
    }

    @Override
    public int waitFor() throws InterruptedException {
        while (!executionChannel.isClosed()) {
            log.debug("Waiting ... ");
            Thread.sleep(1000);
        }

        return executionChannel.getExitStatus();
    }

    @Override
    public int exitValue() {
        return executionChannel.getExitStatus();
    }

    @Override
    public void destroy() {
        executionChannel.disconnect();
    }
}
