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

package org.midonet.vtep

import org.midonet.packets.IPv4Addr

/**
 * A client class for the connection to a VTEP-enabled switch. A client
 * instance allows multiple users to share the same connection to a VTEP,
 * while monitoring the connection for possible failure and including a
 * recovery mechanism.
 */
trait VtepConnection {

    /** @return The VTEP management IP */
    def getManagementIp: IPv4Addr

    /** @return The VTEP management port */
    def getManagementPort: Int

}
