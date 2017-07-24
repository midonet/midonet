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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.cache.state.StateConverter;
import org.midonet.cluster.cache.state.StateOwnership;
import org.midonet.cluster.data.storage.KeyType.KeyTypeVal;

/**
 * Contains the information for a state key, which includes the key type,
 * name and ownership.
 */
class StateKey {

    private static final Logger LOG = LoggerFactory.getLogger(StateKey.class);

    private static final class DefaultConverter implements StateConverter {
    }

    private static final StateConverter DEFAULT_CONVERTER =
        new DefaultConverter();

    private final KeyTypeVal type;
    private final String name;
    private final StateOwnership ownership;
    private final StateConverter converter;

    StateKey(KeyTypeVal type, String name,
             Class<? extends StateOwnership> ownershipClass) {
        this(type, name, ownershipClass, null);
    }

    StateKey(KeyTypeVal type, String name,
             Class<? extends StateOwnership> ownershipClass,
             Class<? extends StateConverter> converterClass) {
        StateOwnership ownership = null;
        try {
            ownership = ownershipClass.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            LOG.error("Cannot create state owner instance for class {}",
                      ownershipClass, e);
        }

        StateConverter converter = DEFAULT_CONVERTER;
        try {
            if (converterClass != null) {
                converter = converterClass.newInstance();
            }
        } catch (InstantiationException | IllegalAccessException e) {
            LOG.error("Cannot create state converter instance for class {}",
                      converterClass, e);
        }

        this.type = type;
        this.name = name;
        this.ownership = ownership;
        this.converter = converter;
    }

    /**
     * @return The state key type.
     */
    public KeyTypeVal type() {
        return type;
    }

    /**
     * @return The state key name.
     */
    public String name() {
        return name;
    }

    /**
     * @return The state {@link StateOwnership} instance.
     */
    public StateOwnership ownership() {
        return ownership;
    }

    /**
     * @return The state {@link StateConverter} instance.
     */
    public StateConverter converter() {
        return converter;
    }

}
