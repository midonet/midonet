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

import org.junit.Assert;
import org.junit.Test;

public class StateConverterTest {

    private static final class TestableConverter implements StateConverter {
    }

    @Test
    public void testDefaults() {
        // Given a testable converter.
        StateConverter converter = new TestableConverter();

        // When converting a single value key.
        byte[] singleValue = new byte[]{1, 2, 3, 4};
        byte[] convertedSingleValue = converter.singleValue(singleValue);

        // Then the values should be equal.
        Assert.assertArrayEquals(singleValue, convertedSingleValue);

        // When converting a multi value key.
        String[] multiValue = new String[]{"1", "2", "3", "4"};
        String[] convertedMultiValue = converter.multiValue(multiValue);

        // Then the values should be equal.
        Assert.assertArrayEquals(multiValue, convertedMultiValue);
    }

}
