package com.midokura.midolman.layer3;

import org.junit.After;
import org.junit.Before;

import com.midokura.midolman.util.Cache;
import com.midokura.midolman.voldemort.VoldemortTester;

public class TestNetworkWithVoldemort extends TestNetwork {

    private VoldemortTester voldemort;

    @Override
    protected Cache createCache() {
        return voldemort.constructCache();
    }

    @Before
    public void setUp() throws Exception {
        voldemort = new VoldemortTester();
        voldemort.setUp();
        super.setUp();
    }

    @After
    public void tearDown() throws Exception {
        voldemort.tearDown();
    }

}
