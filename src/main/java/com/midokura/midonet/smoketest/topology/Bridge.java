/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.topology;

import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

public class Bridge {

    public static class Builder {
        MidolmanMgmt mgmt;

        public Builder(MidolmanMgmt mgmt) {
            this.mgmt = mgmt;
        }

        public Bridge build() {
            return null;
        }
    }

    public void delete() {

    }
}
