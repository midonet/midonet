package org.midonet.brain.southbound.vtep;
/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

import java.util.UUID;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class VtepConstantsTest {

    /**
     * This is enforced by spec, so better to test it.
     */
    @Test
    public void testBridgeIdToLogicalSwitchName() {
        UUID bridgeId = UUID.randomUUID();
        String lsName = VtepConstants.bridgeIdToLogicalSwitchName(bridgeId);
        assertEquals("mn-" + bridgeId, lsName);

        assertEquals(bridgeId,
                     VtepConstants.logicalSwitchNameToBridgeId(lsName));
    }

}
