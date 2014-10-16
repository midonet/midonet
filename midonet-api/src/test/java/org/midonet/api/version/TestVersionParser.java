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
package org.midonet.api.version;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class TestVersionParser {

    private VersionParser testObject;

    private final String input;
    private final int expected;

    public TestVersionParser(String input, int expected) {
        this.input = input;
        this.expected = expected;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> inputs() {

        List<Object[]> params = new ArrayList<Object[]>();

        params.add(new Object[]{
                "application/vnd.org.midonet.Application-v1+json", 1});
        params.add(new Object[]{
                "application/vnd.org.midonet.Application-v10+json", 10});
        params.add(new Object[]{
                "application/vnd.org.midonet.Application-v100+json", 100});
        params.add(new Object[]{
                "application/vnd.org.midonet.Application-v0+json", 0});

        // Error cases
        params.add(new Object[]{
                "application/vnd.org.midonet.Application-v-2+json", -1});
        params.add(new Object[]{"foo", -1});
        params.add(new Object[]{"", -1});

        return params;
    }

    @Before
    public void setup() {
        this.testObject = new VersionParser();
    }

    @Test
    public void testGetVersion() {

        int actual = this.testObject.getVersion(input);
        assertEquals(expected, actual);
    }
}
