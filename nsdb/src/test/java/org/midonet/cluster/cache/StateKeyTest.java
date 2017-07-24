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

import org.junit.Assert;
import org.junit.Test;


import org.midonet.cluster.cache.state.HostStateConverter;
import org.midonet.cluster.cache.state.HostStateOwnership;
import org.midonet.cluster.cache.state.StateConverter;
import org.midonet.cluster.cache.state.StateOwnership;
import org.midonet.cluster.data.storage.KeyType;

public class StateKeyTest {

    @Test
    public void testInstantiableOwnership() {
        // Given an instantiable ownership class.
        Class<? extends StateOwnership> clazz = HostStateOwnership.class;

        // When creating a state key.
        StateKey key = new StateKey(KeyType.SingleLastWriteWins(), "key", clazz);

        // Then the state key has a correct ownership.
        Assert.assertNotNull(key.ownership());

        // And the ownership is of the correct type.
        Assert.assertEquals(key.ownership().getClass(), clazz);

        // And the key type is correct.
        Assert.assertEquals(key.type().isSingle(), true);

        // And the key name is correct.
        Assert.assertEquals(key.name(), "key");

        // And the converter is a default converter.
        Assert.assertNotNull(key.converter());
    }

    @Test
    public void testNonInstantiableOwnership() {
        // Given a non-instantiable ownership class.
        Class<? extends StateOwnership> clazz = StateOwnership.class;

        // When creating a state key.
        StateKey key = new StateKey(KeyType.SingleLastWriteWins(), "key", clazz);

        // Then the key ownership should be null.
        Assert.assertNull(key.ownership());
    }

    @Test
    public void testInstantiableConverter() {
        // Given an instantiable converter class.
        Class<? extends StateConverter> clazz = HostStateConverter.class;

        // When creating a state key.
        StateKey key = new StateKey(KeyType.SingleLastWriteWins(), "key",
                                    HostStateOwnership.class, clazz);

        // Then the converter is a host converter.
        Assert.assertNotNull(key.converter());
        Assert.assertEquals(HostStateConverter.class, key.converter().getClass());
    }

    @Test
    public void testNonInstantiableConverter() {
        // Given a non-instantiable converter class.
        Class<? extends StateConverter> clazz = StateConverter.class;

        // When creating a state key.
        StateKey key = new StateKey(KeyType.SingleFirstWriteWins(), "key",
                                    HostStateOwnership.class, clazz);

        // Then the converter is a default converter.
        Assert.assertNotNull(key.converter());
    }

}
