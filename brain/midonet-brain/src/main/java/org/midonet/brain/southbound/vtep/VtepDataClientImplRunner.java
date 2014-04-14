/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.Random;

import org.midonet.packets.IPv4Addr;

public class VtepDataClientImplRunner {

    public static void main(String[] args) {

        String mgmtIp = "119.15.112.22";
        String midoIp = "119.15.113.90";
        final int port = 6633;

        VtepDataClientImpl vtep = new VtepDataClientImpl();
        vtep.connect(IPv4Addr.fromString(mgmtIp), port);

        System.out.println(vtep.listLogicalSwitches());

        int n = Math.abs(new Random().nextInt()) % 100;
        String vtepName = "lsTest" + n;

        vtep.addLogicalSwitch(vtepName, n);

        try {
            Thread.sleep(500);
            vtep.bindVlan(vtepName, "eth0", n + 1);
            Thread.sleep(500);
            vtep.addMcastMacRemote(vtepName, VtepDataClient.UNKNOWN_DST, midoIp);
            Thread.sleep(500);
            vtep.addUcastMacRemote(vtepName, VtepDataClient.UNKNOWN_DST, midoIp);
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println(vtep.listLogicalSwitches());
        System.out.println(vtep.listPhysicalSwitches());
        System.out.println(vtep.listMcastMacsRemote());
        System.out.println(vtep.listMcastMacsLocal());

        vtep.disconnect();

        System.exit(0);
    }

}
