package org.midonet.odp;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.midonet.odp.flows.FlowKey;
import org.midonet.odp.flows.FlowKeyARP;
import org.midonet.odp.flows.FlowKeyICMP;
import org.midonet.odp.flows.FlowKeyICMPEcho;
import org.midonet.odp.flows.FlowKeyICMPError;
import org.midonet.odp.flows.FlowKeyTCP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FlowMatchTest {

    private List<FlowKey<?>> supported = new ArrayList<FlowKey<?>>();
    private List<FlowKey<?>> unsupported = new ArrayList<FlowKey<?>>();
    private List<FlowKey<?>> tmp = new ArrayList<FlowKey<?>>();

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() {
        supported.add(new FlowKeyICMP());
        supported.add(new FlowKeyARP());
        supported.add(new FlowKeyTCP());
        unsupported.add(new FlowKeyICMPEcho());
        unsupported.add(new FlowKeyICMPError());
    }

    @After
    public void tearDown() {
        supported.clear();
        unsupported.clear();
        tmp = null;
    }

    @Test
    public void testAddKey() {
        FlowMatch m = new FlowMatch();
        assertFalse(m.isUserSpaceOnly());
        for (FlowKey key : supported) {
            m.addKey(key);
        }
        assertFalse(m.isUserSpaceOnly());
        m.addKey(unsupported.get(0));
        assertTrue(m.isUserSpaceOnly());
        assertEquals(supported.size() + 1, m.getKeys().size());
    }

    @Test
    public void testSetKeys() {
        tmp.addAll(supported);
        FlowMatch m = new FlowMatch();
        assertFalse(m.isUserSpaceOnly());
        m.setKeys(tmp);
        assertFalse(m.isUserSpaceOnly());
        m.addKey(unsupported.get(1));
        assertTrue(m.isUserSpaceOnly());
        assertEquals(supported.size() + 1, m.getKeys().size());
    }

    @Test
    public void testConstructWithKeys() {
        tmp.addAll(supported);
        FlowMatch m = new FlowMatch(tmp);
        assertFalse(m.isUserSpaceOnly());
        assertEquals(supported.size(), m.getKeys().size());
        m.addKey(unsupported.get(0));
        assertTrue(m.isUserSpaceOnly());
        assertEquals(supported.size() + 1, m.getKeys().size());

        tmp = new ArrayList<FlowKey<?>>();
        tmp.addAll(supported);
        tmp.addAll(unsupported);
        m = new FlowMatch(tmp);
        assertTrue(m.isUserSpaceOnly());
        assertEquals(supported.size() + unsupported.size(), m.getKeys().size());
    }

}
