package com.midokura.midonet.smoketest.topology;

import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

public class Router {

    public static class Builder {
        MidolmanMgmt mgmt;

        public Builder(MidolmanMgmt mgmt) {
            this.mgmt = mgmt;
        }

        public Builder setName(String string) {
            // TODO Auto-generated method stub
            return null;
        }

        public Router build() {
            // TODO Auto-generated method stub
            return null;
        }

    }

    public PortBuilder addPort() {
        // TODO Auto-generated method stub
        return null;
    }

    public InterRouterLink.Builder addRouterLink() {
        // TODO Auto-generated method stub
        return null;
    }

    public void delete() {
        
    }

}
