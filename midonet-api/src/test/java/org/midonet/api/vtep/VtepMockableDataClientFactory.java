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
package org.midonet.api.vtep;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.common.collect.Sets;

import rx.Subscription;
import rx.functions.Action1;

import org.midonet.cluster.data.vtep.VtepConfigException;
import org.midonet.cluster.data.vtep.VtepConnection;
import org.midonet.cluster.data.vtep.VtepDataClient;
import org.midonet.cluster.southbound.vtep.VtepDataClientFactory;
import org.midonet.cluster.southbound.vtep.VtepDataClientMock;
import org.midonet.cluster.data.vtep.VtepStateException;
import org.midonet.packets.IPv4Addr;

import static java.util.Arrays.*;

/**
 * A VtepDataClientFactory that provides a Mock implementation.
 */
public class VtepMockableDataClientFactory extends VtepDataClientFactory {

    public abstract static class MockableVtep {
        abstract public String mgmtIp();
        abstract public int mgmtPort();
        abstract public String name();
        abstract public String desc();
        abstract public Set<String> tunnelIps();
        abstract public String[] portNames();
    }

    /** This VTEP is always mockable by default */
    public static final MockableVtep MOCK_VTEP1 = new MockableVtep() {
        public String mgmtIp() { return "250.132.36.225"; }
        public int mgmtPort() { return 12345; }
        public String name() { return "Mock Vtep"; }
        public String desc() { return "The mock vtep description"; }
        public Set<String> tunnelIps() {
            return Sets.newHashSet("32.213.81.62",
                                   "197.132.120.121",
                                   "149.150.232.204");
        }
        public String[] portNames() {
            return new String[]{"eth0", "eth1", "eth_2", "Te 0/2"};
        }
    };

    public static final MockableVtep MOCK_VTEP2 = new MockableVtep() {
        public String mgmtIp() { return "30.20.3.99"; }
        public int mgmtPort() { return 3388; }
        public String name() { return "TestVtep-mock-vtep-2"; }
        public String desc() { return "From TestVtep"; }
        public Set<String> tunnelIps() {
            return Sets.newHashSet("197.22.120.100");
        }
        public String[] portNames() {
            return new String[]{"eth0", "eth1", "eth_2", "Te 0/2"};
        }
    };

    private Map<String, MockableVtep> mockableVteps = new HashMap<>();
    private Map<String, VtepDataClientMock> mockInstances = new HashMap<>();
    private Map<String, Subscription> mockSubscriptions = new HashMap<>();

    public VtepMockableDataClientFactory() {
        allowMocking(MOCK_VTEP1);
        allowMocking(MOCK_VTEP2);
    }

    /** Allow mocking the given VTEP in this Factory, after this is called
     * connect() will return a mocked VtepDataClient for the VTEP defined in
     * def. Otherwise, connect will fail. */
    public void allowMocking(MockableVtep def) {
        if (mockableVteps.containsKey(def.mgmtIp())) {
            throw new IllegalStateException(
                "We already have a mock vtep with that mgmt ip");
        }
        mockableVteps.put(def.mgmtIp(), def);
    }

    /**
     * Connects to the VTEP with the specified IP address and transport port
     * (end-point). The method returns a connection-aware VTEP data client.
     *
     * If the use mock value is set in the ZooKeeper configuration, the method
     * returns a mock instance of the VTEP data client.
     *
     * @param mgmtIp The VTEP management IP address.
     * @param mgmtPort The VTE management port.
     * @param owner The owner for this connection.
     * @return The VTEP data client.
     * @throws VtepStateException The client could not connect because of
     * an invalid service state after a number of retries.
     * @throws java.lang.IllegalArgumentException The management address or port
     * are not allowed for the mock instance.
     */
    public VtepDataClient connect(IPv4Addr mgmtIp, int mgmtPort, UUID owner)
        throws VtepStateException, VtepConfigException {
        MockableVtep mockVtep = mockableVteps.get(mgmtIp.toString());
        if (mockVtep == null) {
            throw new IllegalArgumentException(
                "VTEP at " + mgmtIp + " not mockable. Did you forget to " +
                "register it via allowMocking()?");
        } else if (mockVtep.mgmtPort() != mgmtPort) {
            throw new IllegalArgumentException("VTEP at " + mgmtIp +
                                               " registered with different port "
                                               + mockVtep.mgmtPort());
        }

        final String ip = mgmtIp.toString();
        VtepDataClientMock mockClient = mockInstances.get(ip);
        if (mockClient == null) {
            mockInstances.put(
                mgmtIp.toString(),
                mockClient = new VtepDataClientMock(ip, mockVtep.mgmtPort(),
                                        mockVtep.name(), mockVtep.desc(),
                                        mockVtep.tunnelIps(),
                                        asList(mockVtep.portNames()))
            );
            final Subscription s = mockClient.observable().subscribe(
                new Action1<VtepConnection.State$.Value>() {
                    @Override
                    public void call(VtepConnection.State$.Value state) {
                        if (state == VtepConnection.State$.MODULE$.DISPOSED()) {
                            mockSubscriptions.get(ip).unsubscribe();
                            mockInstances.remove(ip);
                            mockSubscriptions.remove(ip);
                        }
                    }
                });
            mockSubscriptions.put(ip, s);

            // Add a non-Midonet switch and binding to test that the
            // Midonet API properly ignores these. Need to call connect()
            // so the mock doesn't throw not-connected errors.
            mockClient.connect(mgmtIp, mgmtPort);
            mockClient.createNonMidonetSwitch("NonMidonetLS", 123456);
            mockClient.createNonMidonetBinding("eth_2", (short) 4000,
                                               "NonMidonetLS");
        } else {
            mockClient.connect(mgmtIp, mgmtPort);
        }

        return mockClient;
    }
}
