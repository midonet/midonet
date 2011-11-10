package com.midokura.midonet.smoketest;

import org.junit.Assert;
import org.junit.Test;

import com.midokura.midolman.openvswitch.*;

public class SmokeTest {

    @Test
    public void test() {
        // 1) Create mock objects:
        //      - smoketest.mocks.MockMidolmanManagement
        //      - ?

        // 2) Make REST calls to create the virtual topology.
        // See midolmanj-mgmt: com.midokura.midolman.mgmt.rest_api.v1.MainTest.java
        // and com.midokura.midolman.mgmt.tools.CreateZkTestConfig

        // 3) Create taps

        // 4) Create OVS datapaths and ports and set externalIds of corresponding
        // vports. Use midolmanj's com.midokura.midolman.openvswitch.
        // OpenvSwitchDatabaseConnectionImpl class (it's defined in scala code).

        // 5) Create a ControllerTrampoline for each datapath. Initially, just one
        // later probably 2. Later modify midolmanj's com.midokura.midolman
        // package to allow different ControllerTrampolines listening on
        // different ports.

        // 6) Ping a port.

        // 7) Tear down the OVS structures and taps.
    }

}
