/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.voldemort;

import java.util.List;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import voldemort.TestUtils;
import voldemort.store.AbstractStorageEngineTest;
import voldemort.store.StorageEngine;
import voldemort.utils.ByteArray;
import voldemort.versioning.Versioned;

public class TestAmnesicStorageEngine extends AbstractStorageEngineTest {

    @Override
    public StorageEngine<ByteArray, byte[], byte[]> getStorageEngine() {
        return new AmnesicStorageEngine<ByteArray, byte[], byte[]>("test", 10000);
    }

    /** Minimum lifetime in milliseconds. Maximum will be twice this. */
    private final int lifetime = 100;

    private AmnesicStorageEngine<Integer, Integer, Object> store;

    private Random random;

    @Before
    public void setUp() throws Exception {
        store = new AmnesicStorageEngine<Integer, Integer, Object>("test",
                lifetime);
        random = new Random(893781999L);
    }

    @After
    public void tearDown() throws Exception {
        store = null;
    }

    @Test
    public void testSimplePutAndGet() {
        store.put(1, new Versioned<Integer>(293), null);
        List<Versioned<Integer>> l = store.get(1, null);
        assertEquals(1, l.size());
        assertEquals(293, (int) l.get(0).getValue());
    }

    @Test(timeout = lifetime)
    // timeout included to test reasonably high throughput
    public void testManyPutAndGet() {
        int values[] = new int[10000];
        for (int i = 0; i < 10000; i++) {
            int value = random.nextInt();
            values[i] = value;
            store.put(i, new Versioned<Integer>(value), null);
        }

        for (int i = 0; i < 10000; i++) {
            int value = values[i];
            List<Versioned<Integer>> l = store.get(i, null);
            assertEquals(1, l.size());
            assertEquals(value, (int) l.get(0).getValue());
        }
    }

    @Test
    public void testSingleExpiration() throws Exception {
        store.put(2894, new Versioned<Integer>(892487), null);
        Thread.sleep(3 * lifetime);
        assertEquals(0, store.get(2894, null).size());
    }

    @Test
    public void testPutReplace() {
        store.put(8374, new Versioned<Integer>(483, TestUtils.getClock(1)),
                null);
        store.put(8374, new Versioned<Integer>(820, TestUtils.getClock(1, 1)),
                null);
        List<Versioned<Integer>> l = store.get(8374, null);
        assertEquals(1, l.size());
        assertEquals(820, (int) l.get(0).getValue());
    }

}
