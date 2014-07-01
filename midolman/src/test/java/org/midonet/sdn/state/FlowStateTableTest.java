/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.sdn.state;

import java.util.*;
import java.util.concurrent.TimeUnit;

import com.yammer.metrics.core.Clock;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class FlowStateTableTest {

    private ShardedFlowStateTable<String, Integer> global;
    private ArrayList<FlowStateLifecycle<String, Integer>> shards = new ArrayList<>();

    private final int SHARDS = 4;

    private final String[] keys =  {"A", "B", "C", "D", "E", "F"};
    private final Integer[] vals = {100, 200, 300, 400, 500, 600};
    private final MockClock clock = new MockClock();


    @Before
    @SuppressWarnings("unchecked")
    public void before() {
        global = new ShardedFlowStateTable<>(clock);
        for (int i = 0; i < SHARDS; i++)
            shards.add((FlowStateLifecycle) global.addShard());
    }

    @Test
    public void testSetGetSingleShard() {
        FlowStateTable<String, Integer> shard = shards.get(0);
        assertThat(shard, notNullValue());

        for (int i = 0; i < keys.length; i++) {
            assertThat(shard.get(keys[i]), nullValue());
            shard.putAndRef(keys[i], vals[i]);
            assertThat(shard.get(keys[i]), equalTo(vals[i]));
        }

        for (int i = 0; i < keys.length; i++)
            assertThat(shard.get(keys[i]), equalTo(vals[i]));

        shard.putAndRef(keys[0], 9595);
        assertThat(shard.get(keys[0]), equalTo(9595));
    }

    @Test
    public void testSetGetMultiShard() {
        for (int i = 0; i < keys.length; i++) {
            assertThat(global.get(keys[i]), nullValue());
            shards.get(i%SHARDS).putAndRef(keys[i], vals[i]);
        }

        for (int i = 0; i < keys.length; i++) {
            for (int shard = 0; shard < SHARDS; shard++) {
                assertThat(shards.get(shard).get(keys[i]), equalTo(vals[i]));
            }
            assertThat(global.get(keys[i]), equalTo(vals[i]));
        }

        shards.get(0).putAndRef(keys[0], 9595);
        for (int shard = 0; shard < SHARDS; shard++) {
            assertThat(shards.get(shard).get(keys[0]), equalTo(9595));
        }
    }

    @Test
    public void testTransactionSetGet() {
        FlowStateTable<String, Integer> shard = shards.get(0);
        FlowStateTransaction<String, Integer> tx =
            new FlowStateTransaction<>(shards.get(0));

        tx.putAndRef("foo", 1);
        assertThat(tx.get("foo"), equalTo(1));
        assertThat(shard.get("foo"), nullValue());

        tx.putAndRef("foo", 2);
        assertThat(tx.get("foo"), equalTo(2));
        assertThat(shard.get("foo"), nullValue());

        tx.putAndRef("bar", 1);
        assertThat(tx.get("bar"), equalTo(1));
        assertThat(shard.get("bar"), nullValue());
    }

    @Test
    public void testTransactionFlush() {
        FlowStateTable<String, Integer> shard = shards.get(0);
        FlowStateTransaction<String, Integer> tx =
                new FlowStateTransaction<>(shards.get(0));

        tx.putAndRef("foo", 1);
        tx.putAndRef("bar", 2);

        tx.flush();

        assertThat(tx.get("foo"), nullValue());
        assertThat(tx.get("bar"), nullValue());
        assertThat(shard.get("foo"), nullValue());
        assertThat(shard.get("bar"), nullValue());
    }

    @Test
    public void testTransactionCommit() {
        FlowStateTable<String, Integer> shard = shards.get(0);
        FlowStateTransaction<String, Integer> tx =
                new FlowStateTransaction<>(shards.get(0));
        tx.putAndRef("foo", 1);
        tx.putAndRef("bar", 2);

        tx.commit();

        assertThat(shard.get("foo"), equalTo(1));
        assertThat(shard.get("bar"), equalTo(2));
    }

    @Test
    public void testTxRefCount() {
        FlowStateLifecycle<String, Integer> shard = shards.get(0);
        FlowStateTransaction<String, Integer> tx =
                new FlowStateTransaction<>(shards.get(0));

        shard.putAndRef("foo", 1);
        shard.putAndRef("bar", 1);
        shard.unref("foo");
        shard.unref("bar");

        clock.time = TimeUnit.MILLISECONDS.toNanos(10);

        tx.ref("foo");
        tx.commit();

        shard.expireIdleEntries(5);

        assertThat(shard.get("foo"), equalTo(1));
        assertThat(shard.get("bar"), nullValue());
    }

    private void foldTest(FlowStateTable<String, Integer> cs) {
        Set<String> txKeys = cs.fold(new HashSet<String>(), new KeyReducer());
        Set<String> expectedKeys = new HashSet<>();
        expectedKeys.addAll(Arrays.asList(keys));
        assertThat(txKeys, equalTo(expectedKeys));

        Set<Integer> txVals = cs.fold(new HashSet<Integer>(), new ValueReducer());
        Set<Integer> expectedVals = new HashSet<>();
        expectedVals.addAll(Arrays.asList(vals));
        assertThat(txVals, equalTo(expectedVals));
    }

    @Test
    public void testTransactionFold() {
        FlowStateTable<String, Integer> tx = new FlowStateTransaction<>(shards.get(0));
        for (int i = 0; i < keys.length; i++)
            tx.putAndRef(keys[i], vals[i]);
        foldTest(tx);
    }

    @Test
    public void testSingleShardFold() {
        for (int i = 0; i < keys.length; i++)
            shards.get(0).putAndRef(keys[i], vals[i]);
        foldTest(shards.get(0));
    }

    @Test
    public void testShardFold() {
        for (int i = 0; i < keys.length; i++)
            shards.get(0).putAndRef(keys[i], vals[i]);
        foldTest(global);
    }


    class KeyReducer implements FlowStateTable.Reducer<String, Integer, Set<String>> {
        @Override
        public Set<String> apply(Set<String> seed, String key, Integer value) {
            seed.add(key);
            return seed;
        }
    }

    class ValueReducer implements FlowStateTable.Reducer<String, Integer, Set<Integer>> {
        @Override
        public Set<Integer> apply(Set<Integer> seed, String key, Integer value) {
            seed.add(value);
            return seed;
        }
    }

    class MockClock extends Clock {
        long time = 0;

        @Override
        public long tick() {
            return time;
        }
    }

    @Test
    public void testRefCountSingleShard() {
        for (int i = 0; i < keys.length; i++) {
            shards.get(0).putAndRef(keys[i], vals[i]);
        }
        refCountTest(shards.get(0));
    }

    @Test
    public void testRefCountMultiShard() {
        for (int i = 0; i < keys.length; i++) {
            shards.get(i % shards.size()).putAndRef(keys[i], vals[i]);
        }
        refCountTest(global);
    }

    private void refCountTest(FlowStateLifecycle<String, Integer> cs) {
        for (int i = 0; i < keys.length; i++) {
            cs.unref(keys[i]);
            cs.ref(keys[i]);
        }

        clock.time = TimeUnit.MILLISECONDS.toNanos(10);
        cs.expireIdleEntries(5);

        for (int i = 0; i < keys.length; i++)
            assertThat(cs.get(keys[i]), equalTo(vals[i]));

        long baseTime = clock.time;

        for (int i = 0; i < keys.length; i++) {
            clock.time += TimeUnit.MILLISECONDS.toNanos(5);
            cs.unref(keys[i]);
            cs.ref(keys[i]);
            clock.time += TimeUnit.MILLISECONDS.toNanos(5);
            cs.unref(keys[i]);
        }

        clock.time += TimeUnit.MILLISECONDS.toNanos(10);

        for (int i = 0; i < keys.length; i++) {
            long idleInstant = baseTime + TimeUnit.MILLISECONDS.toNanos((i+1) * 10);
            int ageMillis = (int) TimeUnit.NANOSECONDS.toMillis(clock.time - idleInstant);

            cs.expireIdleEntries(ageMillis - 1);

            for (int j = 0; j < keys.length; j++) {
                if (j <= i)
                    assertThat(cs.get(keys[j]), nullValue());
                else
                    assertThat(cs.get(keys[j]), equalTo(vals[j]));
            }
        }
        for (int i = 0; i < keys.length; i++)
            assertThat(cs.get(keys[i]), nullValue());
    }
}
