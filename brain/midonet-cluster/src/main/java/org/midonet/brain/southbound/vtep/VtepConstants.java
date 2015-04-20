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

/**
 * Various constants and utility functions related to the interaction between
 * a VTEP and Midonet.
 */
public class VtepConstants {

    /**
     * This is the prefix prepended to the bridge uuid when composing a logical
     * switch name.
     */
    private static final String LOGICAL_SWITCH_PREFIX = "mn-";

    /**
     * Logical switches formed to bind a VTEP port and a Midonet bridge have a
     * name formed as a function of the bridge uuid. This function extracts the
     * uuid from the logical switch name.
     *
     * @param lsName the logical switch name
     * @return the Midonet bridge UUID
     */
    public static UUID logicalSwitchNameToBridgeId(String lsName) {
        try {
            return UUID.fromString(
                lsName.substring(LOGICAL_SWITCH_PREFIX.length())
            );
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * This function yields the name that must be assigned to a logical switch
     * used to bind a VTEP's port with a Midonet bridge.
     *
     * @param uuid the bridge's identifier
     * @return the logical switch name
     */
    public static String bridgeIdToLogicalSwitchName(UUID uuid) {
        return LOGICAL_SWITCH_PREFIX + uuid;
    }

}
