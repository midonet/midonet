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

import org.midonet.cluster.data.storage.KeyType;

/**
 * An NSDB state notification.
 */
public interface StateNotification {

    interface Update extends StateNotification {

        /**
         * @return The object class.
         */
        Class<?> objectClass();

        /**
         * @return The object identifier.
         */
        UUID id();

        /**
         * @return The state key.
         */
        String key();

        /**
         * @return The state type.
         */
        KeyType.KeyTypeVal type();

        /**
         * @return The owner that reported the state for this object. When null,
         * it means that the object has no state.
         */
        @Nullable
        UUID owner();

        /**
         * @return The state data for single value keys.
         */
        byte[] singleData();

        /**
         * @return The state data for multi value keys.
         */
        String[] multiData();
    }

    /**
     * Represents a snapshot of the NSDB state entries.
     */
    final class Snapshot extends ArrayList<Update> implements StateNotification {
    }

}
