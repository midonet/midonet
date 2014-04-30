/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.midonet.handlers;

import mockit.Expectations;
import mockit.Mocked;
import mockit.Verifications;
import org.junit.Before;
import org.junit.Test;

import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.events.MacPortUpdate;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

/**
 * Tests MidoVtepMacPortUpdateHandler.
 */
public class MidoVtepMacPortUpdateHandlerTest {
    private MidoVtepMacPortUpdateHandler handler = null;

    private String midoIpAddrStr = "119.15.113.90";
    private IPv4Addr midoIp = IPv4Addr.fromString(midoIpAddrStr);
    private String mac1Str = "aa:bb:cc:dd:ee:01";
    private MAC mac1 = MAC.fromString(mac1Str);

    @Mocked
    private VtepDataClient vtepDataClient;

    @Before
    public void setUp() {
        handler = new MidoVtepMacPortUpdateHandler();
        handler.setVtepDataClient(this.vtepDataClient);
    }

    @Test
    public void testUpdateHandlerAddsUcastMacRemote() {
        // addUcastMacRemote should be called on VTEP Data Client exactly
        // once.
        new Expectations() {{
            vtepDataClient.addUcastMacRemote(
                    MidoVtepMacPortUpdateHandler.midoVtepName,
                    mac1Str,
                    midoIpAddrStr);
            times = 1;
        }};

        MacPortUpdate update = new MacPortUpdate(mac1, null, midoIp);
        handler.handleMacPortUpdate(update);
    }

    @Test
    public void testUpdateHandlerDeletesUcastMacRemote() {
        MacPortUpdate update =
                new MacPortUpdate(mac1, midoIp, null);
        handler.handleMacPortUpdate(update);

        // Port deletion is not handled at the moment.
        new Verifications() {{
            vtepDataClient.addUcastMacRemote(anyString, anyString, anyString);
            times = 0;
        }};
    }

    @Test
    public void testUpdateHandlerUpdatesUcastMacRemote() {
        MacPortUpdate update = new MacPortUpdate(mac1, midoIp, midoIp);
        handler.handleMacPortUpdate(update);

        // Port update is not handled at the moment.
        new Verifications() {{
            vtepDataClient.addUcastMacRemote(anyString, anyString, anyString);
            times = 0;
        }};
    }
}
