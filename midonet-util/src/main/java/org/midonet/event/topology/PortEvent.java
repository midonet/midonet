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

public class PortEvent extends AbstractVirtualTopologyEvent {

    public static final String eventKey = "org.midonet.event.topology.Port";

    public PortEvent() {
        super(eventKey);
    }

    public void link(UUID id, Object data) {
        handleEvent("LINK", id, data);
    }

    public void unlink(UUID id, Object data) {
        handleEvent("UNLINK", id, data);
    }

    public void bind(UUID id, Object data) {
        handleEvent("BIND", id, data);
    }

    public void unbind(UUID id) {
        handleEvent("UNBIND", id);
    }
}
