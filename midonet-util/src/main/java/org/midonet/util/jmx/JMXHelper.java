/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.util.jmx;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper that can take some of the complexity of dealing with JMX out of the
 * client code.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/16/12
 */
public class JMXHelper {

    private static final Logger log = LoggerFactory
        .getLogger(JMXHelper.class);

    public static final String JMX_REMOTE_CONNECTOR_ADDRESS_KEY =
        "com.sun.management.jmxremote.localConnectorAddress";

    public static JMXConnector newJvmJmxConnectorForPid(int pid) {
        List<VirtualMachineDescriptor> vms = VirtualMachine.list();

        for (VirtualMachineDescriptor desc : vms) {

            try {
                VirtualMachine vm = VirtualMachine.attach(desc);

                if (vm.id().equals("" + pid)) {
                    String connectorAddress =
                        vm.getAgentProperties()
                          .getProperty(JMX_REMOTE_CONNECTOR_ADDRESS_KEY);

                    if (connectorAddress == null) {
                        log.debug(
                            "This JVM ({}) does not have the JMX Agent " +
                                "started. Starting it now.",
                            desc.displayName());

                        // Agent wasn't loaded in the remote VM .. so we load it
                        Properties props = vm.getSystemProperties();
                        String home = props.getProperty("java.home");
                        String agent = home + File.separator + "lib" +
                            File.separator + "management-agent.jar";
                        vm.loadAgent(agent);

                        // Reload the properties and
                        //  get the CONNECTOR_ADDRESS_PROPERTY
                        props = vm.getAgentProperties();
                        connectorAddress =
                            props.getProperty(JMX_REMOTE_CONNECTOR_ADDRESS_KEY);
                    }

                    JMXServiceURL serviceUrl =
                        new JMXServiceURL(connectorAddress);

                    return JMXConnectorFactory.connect(serviceUrl);
                }
            } catch (Exception ex) {
                // we ignore since we want to continue to the
                // next descriptor in the vms descriptor list
                // and there are not more statements after catch
                log.error("Exception while trying to load the agent.", ex);
            }
        }

        return null;
    }

    public static MBeanServerConnection newJmxServerConnectionFromUrl(
        String serverUrl) throws IOException {
        JMXServiceURL url = new JMXServiceURL(serverUrl);
        //Get JMX connector
        JMXConnector jmxc = JMXConnectorFactory.connect(url);
        //Get MBean server connection
        return jmxc.getMBeanServerConnection();
    }

    public static <T> JMXAttributeAccessor.Builder<T> newAttributeAccessor(
        MBeanServerConnection connection, String attributeName) {
        return new JMXAttributeAccessor.Builder<T>(connection, attributeName);
    }
}
