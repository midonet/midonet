/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.topology;

import com.midokura.midolman.mgmt.data.dto.client.DtoAdRoute;
import com.midokura.midolman.mgmt.data.dto.client.DtoBgp;
import com.midokura.midolman.mgmt.data.dto.client.DtoMaterializedRouterPort;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/28/11
 * Time: 1:38 PM
 */
public class Bgp {

    private MidolmanMgmt mgmt;
    private DtoBgp dtoBgp;

    public Bgp(MidolmanMgmt mgmt, DtoBgp dtoBgp) {
        this.mgmt = mgmt;
        this.dtoBgp = dtoBgp;
    }

    public interface Builder {

        public Builder setLocalAs(int localAS);

        public Builder setPeer(int peerAS, String peerAddress);

        Bgp build();
    }

    public AdRoute addAdvertisedRoute(String networkAddress, int prefix) {

        DtoAdRoute dtpAdRoute = new DtoAdRoute();

        dtpAdRoute.setNwPrefix(networkAddress);
        dtpAdRoute.setPrefixLength(prefix);

//        DtoBgp bgp = new DtoBgp();
//
//        bgp.setLocalAS(localAS);
//        bgp.setPeerAS(peerAS);
//        bgp.setPeerAddr(peerAddress);

        return new AdRoute(mgmt, mgmt.addBgpAdvertisedRoute(dtoBgp, dtpAdRoute));
    }
}
