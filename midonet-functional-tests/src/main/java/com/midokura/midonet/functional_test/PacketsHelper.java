/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.functional_test;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import static java.lang.String.format;

import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midonet.functional_test.utils.RemoteTap;
import com.midokura.tools.timed.Timed;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.waitFor;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/27/12
 */
public class PacketsHelper {

    public static Ethernet waitForArpPacket(RemoteTap tap) throws Exception {
        return waitForPacketOfType(ARP.ETHERTYPE, tap);
    }

    public static Ethernet waitForPacketOfType(final short etherType,
                                               final RemoteTap tap)
        throws Exception {

        long totalWait = TimeUnit.SECONDS.toMillis(30);
        long sleepWait = TimeUnit.MILLISECONDS.toMillis(50);

        return
            waitFor(
                format("Wait for an packet of type %xd", etherType),
                totalWait,
                sleepWait,
                new Timed.Execution<Ethernet>() {
                    @Override
                    protected void _runOnce() throws Exception {
                        byte[] frameBytes = tap.recv(getRemainingTime());

                        if (frameBytes != null) {
                            setResult(new Ethernet());
                            getResult().deserialize(
                                ByteBuffer.wrap(frameBytes));
                            setCompleted(
                                getResult().getEtherType() == etherType);
                        }
                    }
                });
    }
}
