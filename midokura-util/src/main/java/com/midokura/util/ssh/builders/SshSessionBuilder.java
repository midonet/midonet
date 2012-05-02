/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.util.ssh.builders;

import java.io.IOException;

import com.midokura.util.ssh.SshHelper;
import com.midokura.util.ssh.commands.SshSession;
import com.midokura.util.ssh.jsch.PasswordCredentialsUserInfo;

/**
 * Abstract command builder that is used by other builder to have a nicely typed
 * fluent interface that is also reusing code.
 *
 * Uses ideas from there: http://weblogs.java.net/node/642849
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/27/12
 */
public abstract class SshSessionBuilder<Builder extends SshSessionBuilder<Builder>>
    implements SshHelper.SshOpBuilder<Builder> {

    protected String hostname;
    protected int port;
    protected String username;
    protected String password;

    protected abstract Builder castThis();

    public Builder onHost(String hostname, int portName) {
        this.hostname = hostname;
        this.port = portName;
        return castThis();
    }

    @Override
    public Builder onHost(String hostname) {
        return onHost(hostname, 22);
    }

    public Builder withCredentials(String username, String password) {
        this.username = username;
        this.password = password;
        return castThis();
    }

    public SshSession open() throws Exception {
        return open(0);
    }

    public SshSession open(int timeout) throws IOException {

        PasswordCredentialsUserInfo userInfo =
            new PasswordCredentialsUserInfo(this.password);

        SshSession sshSession =
            new SshSession(username, hostname, port, userInfo);

        sshSession.openSession(timeout);
        return sshSession;
    }
}
