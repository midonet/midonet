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

import org.midonet.event.AbstractEvent;

import java.util.UUID;

/**
 * Abstract event wrapper class for virtual topology related events, which
 * usually have CREATE, UPDATE, DELETE operations.
 */
public class AbstractVirtualTopologyEvent extends AbstractEvent {

    protected AbstractVirtualTopologyEvent(String key) {
        super(key);
    }

    public void create(UUID id, Object data) {
        handleEvent("CREATE", id, data);
    }

    public void update(UUID id, Object data) {
        handleEvent("UPDATE", id, data);
    }

    public void delete(UUID id) {
        handleEvent("DELETE", id);
    }

    public void delete(UUID id1, UUID id2) {
        handleEvent("DELETE", id1, id2);
    }
}

