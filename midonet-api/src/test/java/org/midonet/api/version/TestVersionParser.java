/*
 * Copyright 2013 Midokura PTE LTD.
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
