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
package org.midonet.midolman.version;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class TestVersionComparator {

    private VersionComparator testObject;

    private final String val1;
    private final String val2;
    private final int expected;

    public TestVersionComparator(String val1, String val2, int expected) {
        this.val1 = val1;
        this.val2 = val2;
        this.expected = expected;
    }
    @Before
    public void setup() {
        testObject = new VersionComparator();
    }

    @Parameters
    public static Collection<Object[]> inputs() {

        List<Object[]> params = new ArrayList<Object[]>();

        params.add(new Object[]{"1.0", "", -1});
        params.add(new Object[]{"1.1", "1.0", 1});
        params.add(new Object[]{"1.11", "1.10", 1});
        params.add(new Object[]{"1.12", "1.5", 1});
        params.add(new Object[]{"0.100", "0.10", 1});
        params.add(new Object[]{"1.0.1", "0.10", 1});

        params.add(new Object[]{"", "1.0", 1});
        params.add(new Object[]{"5.5", "5.6", -1});
        params.add(new Object[]{"1.0", "0.1.0", -1});
        params.add(new Object[]{"12.12", "13.4", -1});
        params.add(new Object[]{"5.0", "5", -1});

        params.add(new Object[]{"", "", 0});
        params.add(new Object[]{"6", "5", 0});
        params.add(new Object[]{"5", "5", 0});
        params.add(new Object[]{"1.1.1", "foo", 0});
        params.add(new Object[]{"1.1", "1.1", 0});
        params.add(new Object[]{"13.1", "13.1", 0});

        return params;
    }

    @Test
    public void testCompare() {

        int actual = testObject.compare(val1, val2);
        assertEquals(expected, actual);

    }

}
