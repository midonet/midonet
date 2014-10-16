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

package org.midonet.event.topology;

import java.util.UUID;

public class BgpEvent extends AbstractVirtualTopologyEvent {

    private static final String eventKey = "org.midonet.event.topology.Bgp";

    public BgpEvent() {
        super(eventKey);
    }

    @Override
    public void update(UUID id, Object data) {
        throw new UnsupportedOperationException();
    }

    /**
     * Overloaded delete method to take data in the 2nd arg
     *
     * @param id
     * @param data
     */
    public void delete(UUID id, Object data) {
        handleEvent("DELETE", id, data);
    }

    public void routeCreate(UUID id, Object data) {
        handleEvent("ROUTE_CREATE", id, data);
    }

    public void routeDelete(UUID id, Object data) {
        handleEvent("ROUTE_DELETE", id, data);
    }
}
