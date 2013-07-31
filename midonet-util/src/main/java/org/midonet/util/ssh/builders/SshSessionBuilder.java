/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.util.ssh.builders;

import java.io.IOException;

import org.midonet.util.ssh.SshHelper;
import org.midonet.util.ssh.commands.SshSession;
import org.midonet.util.ssh.jsch.PasswordCredentialsUserInfo;

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

    public static class BaseSshBuilder extends SshSessionBuilder<BaseSshBuilder> {
        @Override
        protected BaseSshBuilder self() {
            return this;
        }
    }

    protected String hostname;
    protected int port;
    protected String username;
    protected String password;
    protected SshSession sshSession;

    protected abstract Builder self();

    public Builder onHost(String hostname, int portName) {
        this.hostname = hostname;
        this.port = portName;
        return self();
    }

    @Override
    public Builder onHost(String hostname) {
        return onHost(hostname, 22);
    }

    public Builder withCredentials(String username, String password) {
        this.username = username;
        this.password = password;
        return self();
    }

    public SshSession open() throws IOException {
        return open(0);
    }

    public SshSession open(int timeout) throws IOException {

        if (sshSession == null) {
            PasswordCredentialsUserInfo userInfo =
                new PasswordCredentialsUserInfo(this.password);

            sshSession = new SshSession(username, hostname, port, userInfo);

            sshSession.openSession(timeout);
        }

        return sshSession;
    }

    public Builder withSession(SshSession session) {
        this.sshSession = session;
        return self();
    }
}
