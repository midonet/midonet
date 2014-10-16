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

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.packets.MAC;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class TestIpAddrInterfaceSensor {

    @Test
    public void testUpdateInterfaceData() throws Exception {

        IpAddrInterfaceSensor interfaceSensor =
                new IpAddrInterfaceSensor() {
                    @Override
                    protected List<String> getInterfacesOutput() {
                        return Arrays.asList(
                                "1: lo: <LOOPBACK,UP,LOWER_UP> mtu 16436 qdisc noqueue state UNKNOWN",
                                "link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00",
                                "inet 127.0.0.1/8 scope host lo",
                                "inet6 ::1/128 scope host",
                                "valid_lft forever preferred_lft forever",
                                "2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP qlen 1000",
                                "link/ether 08:00:27:c8:c1:f3 brd ff:ff:ff:ff:ff:ff",
                                "inet 172.16.16.16/16 brd 172.16.255.255 scope global eth0:1",
                                "inet 192.168.2.68/24 brd 192.168.2.255 scope global eth0",
                                "inet6 2005::1/64 scope global",
                                "valid_lft forever preferred_lft forever",
                                "inet6 2004::1/10 scope global",
                                "valid_lft forever preferred_lft forever",
                                "inet6 2003::1/10 scope global",
                                "valid_lft forever preferred_lft forever",
                                "inet6 2002::1/10 scope global",
                                "valid_lft forever preferred_lft forever",
                                "inet6 2001::1/10 scope global",
                                "valid_lft forever preferred_lft forever",
                                "inet6 fe80::a00:27ff:fec8:c1f3/64 scope link",
                                "valid_lft forever preferred_lft forever",
                                "3: virbr0: <BROADCAST,MULTICAST> mtu 1500 qdisc noqueue state DOWN",
                                "link/ether 1a:4e:bc:c7:ea:ff brd ff:ff:ff:ff:ff:ff",
                                "inet 192.168.122.1/24 brd 192.168.122.255 scope global virbr0",
                                "5: xxx: <BROADCAST,MULTICAST> mtu 1500 qdisc noop state DOWN qlen 500",
                                "link/ether 4e:07:50:07:55:8a brd ff:ff:ff:ff:ff:ff",
                                "6: eth0.1@eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc noqueue state UP",
                                "link/ether cd:69:7c:64:07:63 brd ff:ff:ff:ff:ff:ff"
                        );
                    }
                };

        Set<InterfaceDescription> interfaces = new TreeSet<>(new Comparator<InterfaceDescription>() {
            @Override
            public int compare(InterfaceDescription o1, InterfaceDescription o2) {
                return 1;
            }
        });
        interfaceSensor.updateInterfaceData(interfaces);

        //Check that we parsed all the interfaces
        assertThat(interfaces.size(), equalTo(5));

        Iterator<InterfaceDescription> it = interfaces.iterator();

        // Check first interface
        InterfaceDescription interfaceDescription = it.next();
        assertThat(interfaceDescription.getName(), equalTo("lo"));
        assertThat(interfaceDescription.isUp(), equalTo(true));
        assertThat(interfaceDescription.getMtu(), equalTo(16436));
        assertThat(interfaceDescription.getMac(), equalTo(MAC.fromString("00:00:00:00:00:00").getAddress()));
        assertThat(interfaceDescription.getInetAddresses().size(), equalTo(2));
        assertThat(interfaceDescription.getInetAddresses().get(0), equalTo(InetAddress.getByName("127.0.0.1")));
        assertThat(interfaceDescription.getInetAddresses().get(1), equalTo(InetAddress.getByName("::1")));
        assertThat(interfaceDescription.getEndpoint(), equalTo(InterfaceDescription.Endpoint.LOCALHOST));

        // Check second interface
        interfaceDescription = it.next();
        assertThat(interfaceDescription.getName(), equalTo("eth0"));
        assertThat(interfaceDescription.isUp(), equalTo(true));
        assertThat(interfaceDescription.getMtu(), equalTo(1500));
        assertThat(interfaceDescription.getMac(), equalTo(MAC.fromString("08:00:27:C8:C1:F3").getAddress()));
        assertThat(interfaceDescription.getInetAddresses().size(), equalTo(8));
        assertThat(interfaceDescription.getInetAddresses().get(0), equalTo(InetAddress.getByName("172.16.16.16")));
        assertThat(interfaceDescription.getInetAddresses().get(1), equalTo(InetAddress.getByName("192.168.2.68")));
        assertThat(interfaceDescription.getInetAddresses().get(2), equalTo(InetAddress.getByName("2005::1")));
        assertThat(interfaceDescription.getInetAddresses().get(3), equalTo(InetAddress.getByName("2004::1")));
        assertThat(interfaceDescription.getInetAddresses().get(4), equalTo(InetAddress.getByName("2003::1")));
        assertThat(interfaceDescription.getInetAddresses().get(5), equalTo(InetAddress.getByName("2002::1")));
        assertThat(interfaceDescription.getInetAddresses().get(6), equalTo(InetAddress.getByName("2001::1")));
        assertThat(interfaceDescription.getInetAddresses().get(7), equalTo(InetAddress.getByName("fe80::a00:27ff:fec8:c1f3")));
        assertThat(interfaceDescription.getEndpoint(), equalTo(InterfaceDescription.Endpoint.UNKNOWN));

        // Check third interface
        interfaceDescription = it.next();
        assertThat(interfaceDescription.getName(), equalTo("virbr0"));
        assertThat(interfaceDescription.isUp(), equalTo(false));
        assertThat(interfaceDescription.getMtu(), equalTo(1500));
        assertThat(interfaceDescription.getMac(), equalTo(MAC.fromString("1A:4E:BC:C7:EA:FF").getAddress()));
        assertThat(interfaceDescription.getInetAddresses().size(), equalTo(1));
        assertThat(interfaceDescription.getInetAddresses().get(0), equalTo(InetAddress.getByName("192.168.122.1")));
        assertThat(interfaceDescription.getEndpoint(), equalTo(InterfaceDescription.Endpoint.UNKNOWN));

        // Check fourth interface
        interfaceDescription = it.next();
        assertThat(interfaceDescription.getName(), equalTo("xxx"));
        assertThat(interfaceDescription.isUp(), equalTo(false));
        assertThat(interfaceDescription.getMtu(), equalTo(1500));
        assertThat(interfaceDescription.getMac(), equalTo(MAC.fromString("4E:07:50:07:55:8A").getAddress()));
        assertThat(interfaceDescription.getInetAddresses().size(), equalTo(0));
        assertThat(interfaceDescription.getEndpoint(), equalTo(InterfaceDescription.Endpoint.UNKNOWN));

        // Check fifth interface
        interfaceDescription = it.next();
        assertThat(interfaceDescription.getName(), equalTo("eth0.1"));
        assertThat(interfaceDescription.isUp(), equalTo(true));
        assertThat(interfaceDescription.getMtu(), equalTo(1500));
        assertThat(interfaceDescription.getMac(), equalTo(MAC.fromString("CD:69:7C:64:07:63").getAddress()));
        assertThat(interfaceDescription.getInetAddresses().size(), equalTo(0));
        assertThat(interfaceDescription.getEndpoint(), equalTo(InterfaceDescription.Endpoint.UNKNOWN));
    }
}
