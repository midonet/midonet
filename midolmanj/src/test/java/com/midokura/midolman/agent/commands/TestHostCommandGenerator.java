/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.commands;

import com.midokura.midolman.agent.command.CommandProperty;
import com.midokura.midolman.agent.state.HostDirectory;
import com.midokura.midolman.packets.MAC;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import static com.midokura.midolman.agent.state.HostDirectory.Command.AtomicCommand.OperationType;

public class TestHostCommandGenerator {

    private final static Logger log =
            LoggerFactory.getLogger(TestHostCommandGenerator.class);

    @Test (expected = DataValidationException.class)
    // Wrong syntax for new interface name
    public void testWrongInterfaceName() throws Exception {
        HostDirectory.Interface currentInterface = getSampleInterface();
        HostDirectory.Interface newInterface = new HostDirectory.Interface(currentInterface);

        newInterface.setName("wrong$interface%name!");

        HostCommandGenerator hostCommandGenerator = new HostCommandGenerator();
        HostDirectory.Command command = hostCommandGenerator.createUpdateCommand(currentInterface, newInterface);
    }

    @Test
    // Two identical interfaces should not create any atomic commands
    public void testIdenticalInterfaces() throws Exception {
        HostDirectory.Interface currentInterface = getSampleInterface();
        HostDirectory.Interface newInterface = new HostDirectory.Interface(currentInterface);

        HostCommandGenerator hostCommandGenerator = new HostCommandGenerator();
        HostDirectory.Command command = hostCommandGenerator.createUpdateCommand(currentInterface, newInterface);

        assertThat(command,
                   allOf(notNullValue(),
                         hasProperty("interfaceName",
                                     allOf(
                                         equalTo(currentInterface.getName()),
                                         equalTo(newInterface.getName())))));

        assertThat(command.getCommandList(), hasSize(0));
    }

    @Test
    // The new interface has a new ip address, check it creates an atomic command
    public void testNewIpAddress () throws Exception {
        HostDirectory.Interface currentInterface = getSampleInterface();
        HostDirectory.Interface newInterface = new HostDirectory.Interface(currentInterface);

        // Add new address
        String newAddress = "172.16.16.17";
        InetAddress[] addresses =
            Arrays.copyOf(currentInterface.getAddresses(),
                          currentInterface.getAddresses().length + 1);

        addresses[addresses.length - 1] = InetAddress.getByName(newAddress);
        newInterface.setAddresses(addresses);

        HostCommandGenerator hostCommandGenerator = new HostCommandGenerator();
        HostDirectory.Command command =
            hostCommandGenerator.createUpdateCommand(currentInterface, newInterface);

        assertThat(command,
                   allOf(notNullValue(),
                         hasProperty("interfaceName",
                                     allOf(
                                         equalTo(currentInterface.getName()),
                                         equalTo(newInterface.getName())))));

        assertThat("The generated command has only atomic command",
                   command.getCommandList(), hasSize(1));

        assertThat("The generated command has the proper atomic command generated",
                   command.getCommandList(),
                   contains(
                       allOf(
                           hasProperty("property", equalTo(CommandProperty.address)),
                           hasProperty("opType", equalTo(OperationType.SET)),
                           hasProperty("value", equalTo(newAddress))
                       )));
    }

    private static HostDirectory.Interface getSampleInterface() {
        HostDirectory.Interface iface = new HostDirectory.Interface();
        iface.setName("Interface0");
        iface.setStatus(1);
        iface.setMac(MAC.fromString("00:01:02:03:04:05").getAddress());
        iface.setEndpoint("lo");
        iface.setId(new UUID(1, 1));
        iface.setType(HostDirectory.Interface.Type.Physical);

        Map<String, String> propertiesMap = new HashMap<String, String>();
        iface.setProperties(propertiesMap);

        try {
            iface.setAddresses(new InetAddress[]{InetAddress.getByName("172.16.16.16")});
        } catch (UnknownHostException e) {
            log.warn("Cannot create IP address.", e);
        }
        return iface;
    }
}
