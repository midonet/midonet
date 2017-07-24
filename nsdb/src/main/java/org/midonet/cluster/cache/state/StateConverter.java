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

package org.midonet.cluster.cache.state;

/**
 * Converts the values of a state key for a topology object to a different
 * format that can be uploaded to the cloud.
 */
public interface StateConverter {

    /**
     * Converts a single value key.
     * @param value The local value.
     * @return The cloud value.
     */
    default byte[] singleValue(byte[] value) {
        return value;
    }

    /**
     * Converts a multi value key.
     * @param value The local value.
     * @return The cloud value.
     */
    default String[] multiValue(String[] value) {
        return value;
    }

}
