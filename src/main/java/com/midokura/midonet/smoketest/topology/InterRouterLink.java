/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.topology;

import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

public class InterRouterLink {

    public static class Builder {
        MidolmanMgmt mgmt;

        public Builder(MidolmanMgmt mgmt) {
            this.mgmt = mgmt;
        }

        public Builder setLocalAddress(String string) {
            // TODO Auto-generated method stub
            return null;
        }

        public Builder setPeer(Router router2) {
            // TODO Auto-generated method stub
            return null;
        }

        public Builder setPeerAddress(String string) {
            // TODO Auto-generated method stub
            return null;
        }

        public Builder setLinkAddressLength(int i) {
            // TODO Auto-generated method stub
            return null;
        }

        public InterRouterLink build() {
            // TODO Auto-generated method stub
            return null;
        }

    }

    public Port getLocalPort() {
        // TODO Auto-generated method stub
        return null;
    }

    public Port getPeerPort() {
        // TODO Auto-generated method stub
        return null;
    }

    public void delete() {
        // TODO Auto-generated method stub

    }

}
