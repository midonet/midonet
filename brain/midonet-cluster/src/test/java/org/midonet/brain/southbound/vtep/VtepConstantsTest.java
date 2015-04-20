/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.brain.southbound.vtep;

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
