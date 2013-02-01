/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.functional_test.utils;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import static java.lang.String.format;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.services.MidonetSudoService;
import org.midonet.services.privsep.InterfaceType;
import org.midonet.services.privsep.TunTapInterface;
import static org.midonet.util.process.ProcessHelper.newProcess;

/**
 * A class that helps control a remote tap device remote (via a tcp connection to
 * the midonet-helper).
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/25/12
 */
public class RemoteTap {

    private static final Logger log = LoggerFactory
        .getLogger(RemoteTap.class);

    TunTapInterface tapInterface;

    public RemoteTap(String name) throws Exception {
        this(name, true);
    }

    public RemoteTap(String deviceName, boolean create)
        throws Exception {

        tapInterface = null;

        if (create) {
            tapInterface =
                MidonetSudoService.getClient()
                                  .createInterface(deviceName,
                                                   InterfaceType.TAP);

            newProcess(
                format("ip link set dev %s arp off multicast off up",
                       tapInterface.getName()))
                .withSudo()
                .logOutput(log, "create_tap")
                .runAndWait();
        }
    }

    public String getName() {
        return tapInterface.getName();
    }

    public void remove() throws Exception {
        MidonetSudoService.getClient()
                          .deleteTapInterface(tapInterface);
    }

    public boolean send(byte[] bytes) {
        try {
            MidonetSudoService.getClient()
                              .writeEthernetFrame(ByteBuffer.wrap(bytes),
                                                  tapInterface);
        } catch (Exception e) {
            log.error("Failed to post a frame on a remote tap", e);
            return false;
        }

        return true;
    }

    public byte[] recv() {
        return recv(TimeUnit.SECONDS.toMillis(10));
    }

    public byte[] recv(long timeOut) {
        byte[] frameBytes = null;
        try {
            ByteBuffer frame =
                MidonetSudoService.getClient()
                                  .readEthernetFrame(tapInterface, timeOut);

            frameBytes = frame.array();
        } catch (Exception e) {
            log.error("Failed to read a packet frame from a remote tap in " +
                          timeOut + " millis", e);
        }

        return frameBytes;
    }
}
