/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import mockit.Delegate;
import mockit.Expectations;
import mockit.Mocked;
import mockit.NonStrictExpectations;
import org.junit.Before;
import org.junit.Test;
import org.midonet.brain.southbound.midonet.MidoVxLanPeer;
import org.midonet.brain.southbound.vtep.VtepBroker;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.VtepDataClientProvider;
import org.midonet.cluster.DataClient;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;
import static org.junit.Assert.assertEquals;

public class VxLanGwBrokerTest {

    private static IPv4Addr vtepMgmtIp = IPv4Addr.fromString("192.168.1.20");
    private static int vtepMgmtPort = 6632;

    private VxLanGwBroker broker;

    private List<MacLocation> emittedFrom1;
    private List<MacLocation> emittedFrom2;

    @Mocked
    private VtepDataClientProvider vtepDataClientProvider;

    @Mocked
    private VtepDataClient vtepClient;

    @Mocked
    private DataClient midoClient;

    private MockVxLanPeer mockPeer1;
    private MockVxLanPeer mockPeer2;

    class MockVxLanPeer implements VxLanPeer {

        Subject<MacLocation, MacLocation> s = PublishSubject.create();
        List<MacLocation> applied = new ArrayList<MacLocation>();

        @Override
        public void apply(MacLocation macLocation) {
            applied.add(macLocation);
        }

        @Override
        public Observable<MacLocation> observableUpdates() {
            return s.asObservable();
        }
    }

    @Before
    public void before() {

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

        broker = new VxLanGwBroker(midoClient, vtepDataClientProvider,
                                   vtepMgmtIp, vtepMgmtPort);
    }

    /**
     * Ensure that the VxLanGwBroker wires peers correctly so that each sends
     * its updates to the other peer.
     */
    @Test
    public void testWiring() {

        final MacLocation m1 = new MacLocation(
            MAC.fromString("ff:ff:ff:ff:01:01"),
            "11111111",
            IPv4Addr.fromString("10.1.1.1")
        );
        final MacLocation m2 = new MacLocation(
            MAC.fromString("ff:ff:ff:ff:02:02"),
            "22222222",
            IPv4Addr.fromString("10.2.2.2")
        );
        final MacLocation m3 = new MacLocation(
            MAC.fromString("ff:ff:ff:ff:03:03"),
            "33333333",
            IPv4Addr.fromString("10.3.3.3")
        );

        mockPeer2.s.onNext(m1);
        mockPeer2.s.onNext(m2);
        mockPeer1.s.onNext(m3);

        assertEquals(mockPeer2.applied, Arrays.asList(m3));
        assertEquals(mockPeer1.applied, Arrays.asList(m1, m2));

    }

}
