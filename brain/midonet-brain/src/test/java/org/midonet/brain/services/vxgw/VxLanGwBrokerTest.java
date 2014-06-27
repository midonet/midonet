/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import com.google.inject.Guice;
import com.google.inject.Injector;

import mockit.Delegate;
import mockit.Expectations;
import mockit.Mocked;
import mockit.NonStrictExpectations;

import org.junit.Before;
import org.junit.Test;

import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.apache.commons.configuration.HierarchicalConfiguration;

import org.midonet.brain.BrainTestUtils;
import org.midonet.brain.southbound.midonet.MidoVxLanPeer;
import org.midonet.brain.southbound.vtep.VtepBroker;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.VtepDataClientProvider;
import org.midonet.brain.southbound.vtep.VtepMAC;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.packets.IPv4Addr;

import static org.junit.Assert.assertEquals;

public class VxLanGwBrokerTest {

    private static IPv4Addr vtepMgmtIp = IPv4Addr.fromString("192.168.1.20");
    private static int vtepMgmtPort = 6632;

    @Mocked
    private VtepDataClientProvider vtepDataClientProvider;

    @Mocked
    private VtepDataClient vtepClient;

    private DataClient midoClient;

    private ZookeeperConnectionWatcher zkConnWatcher;

    private MockVxLanPeer mockPeer1;
    private MockVxLanPeer mockPeer2;

    class MockVxLanPeer implements VxLanPeer {

        Subject<MacLocation, MacLocation> s = PublishSubject.create();
        List<MacLocation> applied = new ArrayList<>();

        @Override
        public void apply(MacLocation macLocation) {
            applied.add(macLocation);
        }

        @Override
        public Observable<MacLocation> observableUpdates() {
            return s.asObservable();
        }
    }

    class MockTunnelZoneState extends TunnelZoneState {
        public MockTunnelZoneState() throws Exception {
            super(UUID.randomUUID(), midoClient, zkConnWatcher,
                  new HostStatePublisher(midoClient, zkConnWatcher),
                  new Random());
        }
    }

    @Before
    public void before() throws Exception {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        BrainTestUtils.fillTestConfig(config);
        Injector injector = Guice.createInjector(
            BrainTestUtils.modules(config));

        Directory directory = injector.getInstance(Directory.class);
        BrainTestUtils.setupZkTestDirectory(directory);

        midoClient = injector.getInstance(DataClient.class);
        zkConnWatcher = new ZookeeperConnectionWatcher();

        mockPeer1 = new MockVxLanPeer();
        mockPeer2 = new MockVxLanPeer();

        new NonStrictExpectations(VtepBroker.class) {{
            VtepBroker vB = new VtepBroker(vtepClient);
            vB.observableUpdates(); result = new Delegate() {
                Observable<MacLocation> observableUpdates() {
                    return mockPeer1.observableUpdates();
                }
            };
            vB.apply((MacLocation)any); result = new Delegate() {
                void apply(MacLocation ml) {
                    mockPeer1.apply(ml);
                }
            };
        }};

        new NonStrictExpectations(MidoVxLanPeer.class) {{
            MidoVxLanPeer mP = new MidoVxLanPeer(midoClient);
            mP.observableUpdates(); result = new Delegate() {
                Observable<MacLocation> observableUpdates() {
                    return mockPeer2.observableUpdates();
                }
            };
            mP.apply((MacLocation)any); result = new Delegate() {
                void apply(MacLocation ml) {
                    mockPeer2.apply(ml);
                }
            };
        }};

        new Expectations () {{
            vtepDataClientProvider.get();
            result = vtepClient; times = 1;
            vtepClient.connect(vtepMgmtIp, vtepMgmtPort);
            times = 1;
        }};

        new VxLanGwBroker(midoClient, vtepDataClientProvider,
                          vtepMgmtIp, vtepMgmtPort,
                          new MockTunnelZoneState());
    }

    /**
     * Ensure that the VxLanGwBroker wires peers correctly so that each sends
     * its updates to the other peer.
     */
    @Test
    public void testWiring() throws Exception {

        final MacLocation m1 = new MacLocation(
            VtepMAC.fromString("ff:ff:ff:ff:01:01"), null,
            "11111111", IPv4Addr.fromString("10.1.1.1")
        );
        final MacLocation m2 = new MacLocation(
            VtepMAC.fromString("ff:ff:ff:ff:02:02"), null,
            "22222222", IPv4Addr.fromString("10.2.2.2")
        );
        final MacLocation m3 = new MacLocation(
            VtepMAC.fromString("ff:ff:ff:ff:03:03"), null,
            "33333333", IPv4Addr.fromString("10.3.3.3")
        );

        mockPeer2.s.onNext(m1);
        mockPeer2.s.onNext(m2);
        mockPeer1.s.onNext(m3);

        assertEquals(mockPeer2.applied, Arrays.asList(m3));
        assertEquals(mockPeer1.applied, Arrays.asList(m1, m2));
    }
}
