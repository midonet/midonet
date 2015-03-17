/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.services.c3po;

import java.util.Objects;
import java.util.UUID;

/**
 * A data container for the state of the MidoNet model translation and import
 * service.
 */
public class C3POState {

    // ID for the singleton C3POState object. All objects persisted via
    public static final UUID ID = new UUID(0L, 1L);
    public static final int NO_TASKS_PROCESSED = 0;


    // The storage library needs objects to provide an id
    public final UUID id = ID;
    public final int lastProcessedTaskId;

    public static C3POState at(int lastProcessedTaskId) {
        return new C3POState(lastProcessedTaskId);
    }

    public static C3POState noTasksProcessed() {
        return new C3POState();
    }

    private C3POState(int lastProcessedTaskId) {
        this.lastProcessedTaskId = lastProcessedTaskId;
    }

    /** Only here to support the deserializer, please use the static methods
     * above. */
    public C3POState() {
        this.lastProcessedTaskId = NO_TASKS_PROCESSED;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) {
            return false;
        }
        if (!(o instanceof C3POState)) {
            return false;
        }
        C3POState that = (C3POState)o;
        return Objects.equals(id, that.id) &&
               Objects.equals(lastProcessedTaskId, that.lastProcessedTaskId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, lastProcessedTaskId);
    }

}
