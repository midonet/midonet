/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;
import static org.junit.Assert.assertEquals;

public class VxLanGwBrokerTest {

    private VxLanGwBroker broker;

    private MockVxLanPeer peer1;
    private MockVxLanPeer peer2;
    private List<MacLocation> emittedFrom1;
    private List<MacLocation> emittedFrom2;

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
        peer1 = new MockVxLanPeer();
        peer2 = new MockVxLanPeer();
        broker = new VxLanGwBroker(peer1, peer2);
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

        broker.setupWiring();

        peer1.s.onNext(m1);
        peer1.s.onNext(m2);
        peer2.s.onNext(m3);

        assertEquals(peer1.applied, Arrays.asList(m3));
        assertEquals(peer2.applied, Arrays.asList(m1, m2));

    }

}
