package com.midokura.midonet.functional_test.topology;

import com.midokura.midonet.client.dto.DtoAdRoute;
import com.midokura.midonet.client.dto.DtoAdRoute;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

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
