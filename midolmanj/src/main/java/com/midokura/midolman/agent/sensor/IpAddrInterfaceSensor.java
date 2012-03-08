/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.sensor;

import com.midokura.midolman.agent.interfaces.InterfaceDescription;
import com.midokura.util.process.ProcessHelper;

import java.util.Collections;
import java.util.List;

public class IpAddrInterfaceSensor implements InterfaceSensor {

    ///////////////////////////////////////////////////////////////////////////
    // Public methods
    ///////////////////////////////////////////////////////////////////////////
    @Override
    public List<InterfaceDescription> updateInterfaceData(List<InterfaceDescription> interfaces) {
        if (interfaces == null) {
            return Collections.emptyList();
        }

        // Remove old entries
        interfaces.clear();

        List<String> interfacesOutput = getInterfacesOutput();
        parseInterfaces(interfacesOutput, interfaces);

        return interfaces;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Protected methods
    ///////////////////////////////////////////////////////////////////////////
    protected List<String> getInterfacesOutput() {
        return ProcessHelper.executeCommandLine("ip addr");
    }

    ///////////////////////////////////////////////////////////////////////////
    // Private methods
    ///////////////////////////////////////////////////////////////////////////
    private void parseInterfaces(List<String> interfacesOutput, List<InterfaceDescription> interfaces) {
        for (String line : interfacesOutput) {
            parseInterfaceLine(line, interfaces);
        }
    }

    private void parseInterfaceLine(String line, List<InterfaceDescription> interfaces) {
        String[] tokens = line.trim().split(" ");

        // Sanity check
        if (tokens.length < 2) {
            return;
        }

        if (tokens[1].endsWith(":")) {
            // This output line contains the interface name
            // Create an interfaceDescription with that name
            InterfaceDescription interfaceDescription = new InterfaceDescription(tokens[1].replaceAll(":$", ""));
            parseInterfaceLine(line, interfaceDescription);
            interfaces.add(interfaceDescription);
        } else {
            parseInterfaceLine(line, interfaces.get(interfaces.size()-1));
        }
    }

    private void parseInterfaceLine(String line, InterfaceDescription interfaceDescription) {
        if (interfaceDescription == null) {
            return;
        }

        String[] tokens = line.trim().split(" ");
        if (tokens.length <= 0) {
            return;
        }

        if (tokens[0].endsWith(":")) {
            // this is the first line of the interface output
            // sample:
            // 2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP qlen 1000

            // sanity check
            if (tokens.length < 9) {
                return;
            }

            // Get MTU
            int mtu = Integer.parseInt(tokens[4]);
            interfaceDescription.setMtu(mtu);

            // Get status
            boolean isUp = hasUp(tokens[2]);
            if (tokens[8].matches("UP") || isUp) {
                interfaceDescription.setUp(true);
                interfaceDescription.setHasLink(true);
            } else {
                interfaceDescription.setUp(false);
                interfaceDescription.setHasLink(false);
            }

            // Check if it's localhost interface
            if (tokens[1].replaceAll(":", "").equals("lo")) {
                interfaceDescription.setEndpoint(InterfaceDescription.Endpoint.LOCALHOST);
            }

        } else if (tokens[0].startsWith("link")) {
            // this line contains L2 address (MAC)
            // sample:
            // link/ether 08:00:27:c8:c1:f3 brd ff:ff:ff:ff:ff:ff


            // sanity check
            if (tokens.length < 4) {
                return;
            }

            // Get MAC
            interfaceDescription.setMac(tokens[1]);

        } else if (tokens[0].equals("inet6")) {
            // this line contains IPv6 info
            // sample:
            // inet6 fe80::a00:27ff:fec8:c1f3/64 scope link

            // sanity check
            if (tokens.length < 4) {
                return;
            }

            // IPv6 address
            String[] ipv6address = tokens[1].split("/");
            interfaceDescription.setInetAddress(ipv6address[0]);

        } else if (tokens[0].equals("inet")) {
            // this line contains IPv4 info
            // sample:
            // inet 172.16.16.16/16 brd 172.16.255.255 scope global eth0:1

            // sanity check
            if (tokens.length < 2) {
                return;
            }

            // IPv4 address
            String[] ipv4address = tokens[1].split("/");
            interfaceDescription.setInetAddress(ipv4address[0]);

        } else {
            // line not recognized, skipping
        }

    }

    private boolean hasUp (String status) {
        String[] tokens = status.trim().split("[,<>]");
        for (String token : tokens) {
             if (token.matches("UP")) {
                 return true;
             }
        }
        return false;
    }
}
