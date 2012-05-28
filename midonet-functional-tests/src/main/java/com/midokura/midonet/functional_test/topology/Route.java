/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.topology;

import com.midokura.midolman.mgmt.data.dto.client.DtoRoute;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

import java.util.UUID;

public class Route {

    private MidolmanMgmt mgmt;
    private DtoRoute dtoRoute;

    public Route(MidolmanMgmt mgmt, DtoRoute dtoRoute) {
        this.mgmt = mgmt;
        this.dtoRoute = dtoRoute;
    }

    public UUID getId() {
        return dtoRoute.getId();
    }

    public String getDstNetworkAddr() {
        return dtoRoute.getDstNetworkAddr();
    }

    public int getDstNetworkLength() {
        return dtoRoute.getDstNetworkLength();
    }

    public static class Builder {

        public Builder setDestination(String string) {
            // TODO Auto-generated method stub
            return null;
        }

        public Builder setDestinationLength(int i) {
            // TODO Auto-generated method stub
            return null;
        }

        public Route build() {
            return null;
        }

    }

    @Override
    public String toString() {
        return "Route{" +
            "dtoRoute=" + dtoRoute +
            '}';
    }

    public void delete() {
    }
}
