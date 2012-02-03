package com.midokura.midonet.smoketest.topology;

import com.midokura.midolman.mgmt.data.dto.client.DtoAdRoute;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/28/11
 * Time: 5:51 PM
 */
public class AdRoute {

    private MidolmanMgmt mgmt;
    private DtoAdRoute dtoAdRoute;

    public AdRoute(MidolmanMgmt mgmt, DtoAdRoute dtoAdRoute) {
        this.mgmt = mgmt;
        this.dtoAdRoute = dtoAdRoute;
    }
}
