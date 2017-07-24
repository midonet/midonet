/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster.cache;

import java.util.ArrayList;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.protobuf.Message;

import org.apache.curator.framework.recipes.cache.ChildData;

/**
 * An NSDB object notification.
 */
public interface ObjectNotification {

    /**
     * Represents a single update to an NSDB object.
     */
    interface Update extends ObjectNotification {

        /**
         * @return The object class.
         */
        Class<?> objectClass();

        /**
         * @return The object identifier.
         */
        UUID id();

        /**
         * @return The object data.
         */
        @Nullable
        ChildData childData();

        /**
         * @return The object Protocol Buffers data.
         */
        Message message();

        /**
         * @return True if the object has been deleted.
         */
        boolean isDeleted();

        /**
         * @return The tenant identifier for this object.
         */
        default String tenantId() {
            return ObjectMessaging.tenantOf(message());
        }
    }

    /**
     * Represents a snapshot of the NSDB objects.
     */
    final class Snapshot extends ArrayList<Update> implements ObjectNotification {
    }

}
