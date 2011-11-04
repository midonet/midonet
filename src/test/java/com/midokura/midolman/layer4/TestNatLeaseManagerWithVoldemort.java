package com.midokura.midolman.layer4;

import org.junit.After;
import org.junit.Before;

import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.TestVoldemortCache;

public class TestNatLeaseManagerWithVoldemort extends TestNatLeaseManager {

    private TestVoldemortCache voldemort;

    @Override
    protected Cache createCache() {
        return voldemort.constructCache();
    }

    @Before
    public void setUp() throws Exception {
        voldemort = new TestVoldemortCache();
        voldemort.setUpVoldemort();
        super.setUp();
    }
    
    @After
    public void tearDown() {
        voldemort.tearDownVoldemort();
    }
}
