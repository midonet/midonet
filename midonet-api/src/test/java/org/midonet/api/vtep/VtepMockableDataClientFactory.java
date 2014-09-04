/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.vtep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import com.google.common.collect.Sets;

import rx.Subscription;
import rx.functions.Action1;

import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.VtepDataClientFactory;
import org.midonet.brain.southbound.vtep.VtepDataClientMock;
import org.midonet.brain.southbound.vtep.VtepStateException;
import org.midonet.packets.IPv4Addr;

/**
 * A VtepDataClientFactory that provides a Mock implementation.
 */
public class VtepMockableDataClientFactory extends VtepDataClientFactory {

    public static final String MOCK_VTEP_MGMT_IP = "250.132.36.225";
    public static final int MOCK_VTEP_MGMT_PORT = 12345;
    public static final String MOCK_VTEP_NAME = "Mock VTEP";
    public static final String MOCK_VTEP_DESC = "This is a mock VTEP";
    public static final Set<String> MOCK_VTEP_TUNNEL_IPS = Sets.newHashSet(
            "32.213.81.62", "197.132.120.121", "149.150.232.204");
    public static final String[] MOCK_VTEP_PORT_NAMES =
            new String[]{"eth0", "eth1", "eth_2", "Te 0/2"};

    private VtepDataClientMock mockInstance = null;
    private Subscription mockSubscription = null;

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
        throws VtepStateException {
        // Currently support mock only for MOCK_VTEP_MGMT_IP.
        if (!mgmtIp.toString().equals(MOCK_VTEP_MGMT_IP) ||
            mgmtPort != MOCK_VTEP_MGMT_PORT) {
            throw new IllegalArgumentException("Could not connect to VTEP");
        }
        if (mockInstance == null) {
            mockInstance = new VtepDataClientMock(
                MOCK_VTEP_MGMT_IP, MOCK_VTEP_MGMT_PORT,
                MOCK_VTEP_NAME, MOCK_VTEP_DESC,
                MOCK_VTEP_TUNNEL_IPS,
                Arrays.asList(MOCK_VTEP_PORT_NAMES));
            mockSubscription = mockInstance.stateObservable().subscribe(
                new Action1<VtepDataClient.State>() {
                    @Override
                    public void call(VtepDataClient.State state) {
                        if (state == VtepDataClient.State.DISCONNECTED) {
                            mockSubscription.unsubscribe();
                            mockInstance = null;
                            mockSubscription = null;
                        }
                    }
                });

            // Add a non-Midonet switch and binding to test that the
            // Midonet API properly ignores these. Need to call connect()
            // so the mock doesn't throw not-connected errors.
            mockInstance.connect(IPv4Addr.fromString(MOCK_VTEP_MGMT_IP),
                                 MOCK_VTEP_MGMT_PORT);
            mockInstance.addLogicalSwitch("NonMidonetLS", 123456);
            mockInstance.bindVlan("NonMidonetLS", "eth2", (short)4000,
                                  123456, new ArrayList<IPv4Addr>());
        }
        return mockInstance;
    }
}
