/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.host.sensor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.util.process.ProcessHelper;
import static org.midonet.midolman.host.interfaces.InterfaceDescription.Endpoint;
import static org.midonet.midolman.host.interfaces.InterfaceDescription.Type;

public class IpTuntapInterfaceSensor implements InterfaceSensor{

    public final static Pattern TUN_TAP_PATTERN =
        Pattern.compile("^([^:]+):.*(tun|tap).*$");

    @Override
    public void updateInterfaceData(Set<InterfaceDescription> interfaces) {

        Map<String, Boolean> tunTapDevices = extractTunTapInfo();

        for (InterfaceDescription interfaceDescription : interfaces) {
            // Only update endpoints to those interfaces who don't already have it
            if (interfaceDescription.getEndpoint() == Endpoint.UNKNOWN) {
                // Is this a Tuntap interface?

                if (tunTapDevices.containsKey(interfaceDescription.getName())) {
                    interfaceDescription.setType(Type.VIRT);
                    interfaceDescription.setEndpoint(Endpoint.TUNTAP);
                }
            }
        }
    }

    private Map<String, Boolean> extractTunTapInfo() {
        Map<String, Boolean> tunTapInfo =
            new HashMap<String, Boolean>();

        for (String outputLine : getTuntapOutput()) {
            Matcher matcher = TUN_TAP_PATTERN.matcher(outputLine);
            if ( matcher.matches() ) {
                tunTapInfo.put(matcher.group(1), matcher.group(2).equals("tap"));
            }
        }

        return tunTapInfo;
    }

    protected List<String> getTuntapOutput() {
        return ProcessHelper.executeCommandLine("ip tuntap").consoleOutput;
    }
}
