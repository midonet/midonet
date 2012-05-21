/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.remote;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.ssh.SshHelper;
import com.midokura.util.ssh.commands.SshSession;

/**
 * Class that will handle the specification and setup of the remote host
 * management functionality of the controller.
 * <p/>
 * This will be able to read and parse a remote host specification from the
 * <em>midolman.managed.host</em> system property and it will be able to present
 * the information (if parsed successfully) from there as a parsed object.
 * <p/>
 * The remote host specification is a string having up to 4 distinct pieces of
 * information: username, password, hostname, port where the password and
 * the port are optional.
 * <p/>
 * Note: ssh login without a password is not implemented at this moment.
 * <p/>
 * Example of valid specifications:
 * <ul>
 * <li>user@host</li>
 * <li>user@host:8022</li>
 * <li>user:password@host</li>
 * <li>user:password@host:22</li>
 * </ul>
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/30/12
 */
public class RemoteHost {

    private static final Logger log = LoggerFactory
        .getLogger(RemoteHost.class);

    private static Pattern REMOTE_HOST_SPECIFICATION_PATTERN =
        Pattern.compile("" +
                            "([^:]+)     # match everything up to ':' \n" +
                            "(?:         # optionally (non grouping)  \n" +
                            "   :        # match ':'                  \n" +
                            "   (.+)     # everything until '@'       \n" +
                            ")?" +
                            "@           # match '@'                  \n" +
                            "([^:]+)     # match everything up to ':' \n" +
                            "(?:         # optionally (non grouping)  \n" +
                            "   :        # match ':'                  \n" +
                            "   (\\d+)   # all digits until the end   \n" +
                            ")?",
                        Pattern.COMMENTS);

    private static final String SECTION_REMOTE_HOST = "remote_host";
    private static final String SECTION_FORWARDED_LOCAL_PORTS = "forwarded_local_ports";
    private static final String SECTION_FORWARDED_REMOTE_PORTS = "forwarded_remote_ports";
    private static final String SECTION_MIDONET_PRIVSEP = "midonet_privsep";

    private static RemoteHost remoteHost;
    private boolean isValid = false;

    private String hostName;
    private int hostPort = 22;
    private String userName;
    private String userPass;

    private SshSession session;

    private HierarchicalINIConfiguration config;

    public SshSession getSession() throws IOException {
        if (session == null) {
            session = SshHelper.newSession()
                               .onHost(hostName, hostPort)
                               .withCredentials(userName, userPass)
                               .open();
        }

        return session;
    }

    public String getMidonetHelperPath(String defaultMidonetHelperPath) {

        if (isValid() && config.getSections()
                               .contains(SECTION_MIDONET_PRIVSEP)) {
            return config.configurationAt(SECTION_MIDONET_PRIVSEP)
                         .getString("path", defaultMidonetHelperPath);
        }

        return defaultMidonetHelperPath;
    }

    private Map<Integer, TargetPort> localPortForwards =
        new HashMap<Integer, TargetPort>();

    private Map<Integer, TargetPort> remotePortForwards =
        new HashMap<Integer, TargetPort>();

    public synchronized static RemoteHost getSpecification() {
        if (remoteHost != null)
            return remoteHost;

        remoteHost = new RemoteHost();

        URL configFileUrl =
            RemoteHost.class.getResource("/managed_host.properties");

        File file = new File("managed_host.properties");
        if (file.exists() && file.isFile() && file.canRead()) {
            try {
                configFileUrl = file.toURI().toURL();
            } catch (MalformedURLException e) {
                log.debug("Filed to convert file {} to URL.",
                          file.getAbsolutePath(), e);
            }
        }

        if (configFileUrl != null) {
            try {
                HierarchicalINIConfiguration config =
                    new HierarchicalINIConfiguration(configFileUrl);

                remoteHost.setConfig(config);
            } catch (ConfigurationException e) {
                log.error("Could not parse file: {}", configFileUrl, e);
            }

            remoteHost.parseHostSpecification();
            remoteHost.parsePortForwardings();

            remoteHost.setupPortForwardings();
        }

        return remoteHost;
    }

    private void setupPortForwardings() {
        if (!isValid())
            return;

        try {
            SshSession session = getSession();

            for (Integer localPort : localPortForwards.keySet()) {
                TargetPort target = localPortForwards.get(localPort);

                try {
                    session.setLocalPortForward(
                        localPort, target.targetHost, target.targetPort);
                } catch (Exception e) {
                    log.debug("Could not forward local port {} to {}",
                              localPort, target);
                }
            }

            for (Integer remotePort : remotePortForwards.keySet()) {
                TargetPort target = remotePortForwards.get(remotePort);

                try {
                    session.setRemotePortForward(
                        remotePort, target.targetHost, target.targetPort);
                } catch (Exception e) {
                    log.debug("Could not forward remote port {} to {}",
                              remotePort, target);
                }
            }
        } catch (Exception e) {
            log.error("Exception while connecting to the remote host {} in " +
                          "order to setup port forwardings", getSafeName(), e);
        }
    }

    private void parsePortForwardings() {
        if (!isValid())
            return;

        localPortForwards = parsePortsSection(SECTION_FORWARDED_LOCAL_PORTS);
        remotePortForwards = parsePortsSection(SECTION_FORWARDED_REMOTE_PORTS);
    }

    private Map<Integer, TargetPort> parsePortsSection(String sectionName) {
        Map<Integer, TargetPort> portsMap = new HashMap<Integer, TargetPort>();

        if (config.getSections().contains(sectionName)) {

            HierarchicalConfiguration localPortsConfig =
                config.configurationAt(sectionName);

            Iterator<String> keyIterator = localPortsConfig.getKeys();

            while (keyIterator.hasNext()) {
                String key = keyIterator.next();

                // TODO (mtoader@midokura.com): handle proper port mapping spec
                // right now we are assuming that the target port is always at
                // localhost:port where port is the source port.
                String value = localPortsConfig.getString(key, key);

                try {
                    int port = Integer.parseInt(key);
                    portsMap.put(port, new TargetPort("localhost", port));
                } catch (NumberFormatException ex) {
                    log.debug("Could not parse port {} from section {}",
                              key, sectionName);
                }
            }
        }

        return portsMap;
    }

    private void parseHostSpecification() {
        if (config.getSections().contains(SECTION_REMOTE_HOST)) {
            String spec =
                config.configurationAt(SECTION_REMOTE_HOST)
                      .getString("specification", "");

            if (spec.trim().length() > 0) {
                remoteHost.parseSpecification(spec);
            }
        }
    }

    protected void parseSpecification(String specification) {
        if (specification == null)
            specification = "";

        isValid = false;
        Matcher m = REMOTE_HOST_SPECIFICATION_PATTERN.matcher(specification);
        if (m.matches()) {
            this.userName = m.group(1);
            this.userPass = m.group(2);
            this.hostName = m.group(3);
            if (m.group(4) != null) {
                this.hostPort = Integer.parseInt(m.group(4));
            }
            this.isValid = true;
        }
    }

    public boolean isValid() {
        return isValid;
    }

    public String getSafeName() {
        return String.format("%s@%s:%d", userName, hostName, hostPort);
    }

    public String getHostName() {
        return hostName;
    }

    public int getHostPort() {
        return hostPort;
    }

    public String getUserName() {
        return userName;
    }

    public String getUserPass() {
        return userPass;
    }

    private void setConfig(HierarchicalINIConfiguration config) {
        this.config = config;
    }

    class TargetPort {
        String targetHost;
        int targetPort;

        public TargetPort(String targetHost, int targetPort) {
            this.targetHost = targetHost;
            this.targetPort = targetPort;
        }

        @Override
        public String toString() {
            return "TargetPort{" +
                "targetHost='" + targetHost + '\'' +
                ", targetPort=" + targetPort +
                '}';
        }
    }
}
