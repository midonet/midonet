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

package org.midonet.util;

import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class RangeTest {

    @Test(expected = IllegalArgumentException.class)
    public void testIllegalConstruction() {
        new Range<Integer>(10, 9);
    }

    @Test
    public void testInside() {
        Range<Integer> r = new Range<Integer>(10, 100);
        assertEquals(Integer.valueOf(10), r.start());
        assertEquals(Integer.valueOf(100), r.end());
        assertFalse(r.isInside(1));
        assertFalse(r.isInside(110));
        assertTrue(r.isInside(15));
        assertTrue(r.isInside(10));
        assertTrue(r.isInside(100));
    }

    @Test
    public void testInsideWithNullBounds() {
        Range<Integer> r = new Range<Integer>(100, null);
        Assert.assertTrue(r.isInside(101));
        Assert.assertTrue(r.isInside(123123));
        Assert.assertFalse(r.isInside(99));
        r = new Range<Integer>(null, 100);
        Assert.assertTrue(r.isInside(1));
        Assert.assertTrue(r.isInside(99));
        Assert.assertFalse(r.isInside(101));
    }

    @Test
    public void testEquals() {
        Range<Integer> r1 = new Range<Integer>(10, 100);
        Range<Integer> r2 = new Range<Integer>(10, 100);
        Range<Integer> r3 = new Range<Integer>(10, 103);
        assertEquals(r1, r2);
        assertEquals(r1, r1);
        assertEquals(r2, r1);
        assertNotSame(r1, r3);
    }

}
