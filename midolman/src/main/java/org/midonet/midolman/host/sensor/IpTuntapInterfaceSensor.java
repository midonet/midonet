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
