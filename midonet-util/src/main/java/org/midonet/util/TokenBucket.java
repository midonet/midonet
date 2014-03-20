/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.util;

import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Arrays.copyOf;

/*
 * This class implements a hierarchical token bucket. Starting from a root
 * bucket, it allows a tree of buckets to be created. Tokens go into the root
 * bucket according to the specified fill rate stratagy and are distributed
 * recursively among the root's children. When all the buckets in a level of
 * the hierarchy are full, they accumulate in the parent bucket. Tokens can
 * only be retrieved from the leaf buckets, although you can wait for any
 * bucket to contain a specified number of tokens.
 */
public abstract class TokenBucket {
    public static int TIMEOUT = -1;

    protected final PaddedAtomicInteger numTokens = new PaddedAtomicInteger();
    protected int maxTokens;

    private final ReentrantLock lock = new ReentrantLock();
    protected TokenBucket parent;
    protected int numChildren;
    protected TokenBucket[] children = new TokenBucket[0];

    protected TokenBucket(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public static TokenBucket create(int maxTokens,
                                     TokenBucketFillRate rate) {
        return new RootTokenBucket(maxTokens, rate);
    }

    public static TokenBucket bottomless() {
        return new TokenBucket(Integer.MAX_VALUE) {
            @Override
            public int tryGet(int tokens) {
                return tokens;
            }

            @Override
            protected void borrowTokens() {
                for (TokenBucket tb : children) {
                    if (tb != null)
                        tb.addTokens(Integer.MAX_VALUE);
                }
            }
        };
    }

    public final TokenBucket link(int maxTokens) {
        lock.lock();
        try {
            TokenBucketImpl ntb = new TokenBucketImpl(maxTokens, this);
            int idx = findFreeIndex();
            if (idx < 0) {
                idx = children.length;
                growArray();
            }

            children[idx] = ntb;
            numChildren += 1;

            return ntb;
        } finally {
            lock.unlock();
        }
    }

    public final void unlink(TokenBucket tb) {
        lock.lock();
        try {
            if (tb.parent == null)
                return;

            for (int i = 0; i < children.length; ++i) {
                if (children[i] == tb) {
                    children[i] = null;
                    tb.parent = null;
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        int ts;
        do {
            ts = numTokens.get();
        } while (ts > maxTokens && !numTokens.compareAndSet(ts, maxTokens));
    }

    public int getNumTokens() {
        return numTokens.get();
    }

    public abstract int tryGet(int tokens);

    public static int getAny(final TokenBucket[] buckets,
                             final int[] tokens,
                             final int timeoutMillis) {
        long time = TimeUnit.MILLISECONDS.toNanos(1);
        long deadline = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) +
                        System.nanoTime();

        while (System.nanoTime() < deadline) {
            for (int i = 0; i < buckets.length; ++i) {
                TokenBucket tb = buckets[i];

                if (tb.numTokens.get() >= tokens[i])
                    return i;

                tb.borrowTokens();

                if (tb.numTokens.get() >= tokens[i])
                    return i;
            }
            LockSupport.parkNanos(time);
        }

        return TIMEOUT;
    }

    private void growArray() {
        children = copyOf(children, Math.max(4, children.length << 1));
    }

    private int findFreeIndex() {
        for (int i = 0; i < children.length; ++i) {
            if (children[i] == null)
                return i;
        }
        return -1;
    }

    // There's a race between distributing excess tokens and linking or
    // unlinking a token bucket, which we ignore. There's also a race
    // between a thread that's distributing tokens and a thread that calls
    // borrowTokens(); we also ignore it and let the callers fallback to
    // waiting.
    protected abstract void borrowTokens();

    protected int giveTokens(final int tokens) {
        if (hasChildren()) {
            int excess = distribute(tokens, numChildren, children) +
                         numTokens.getAndSet(0);
            return excess == 0 ? 0 : addTokens(excess);
        }
        return addTokens(tokens);
    }

    private boolean hasChildren() {
        return numChildren > 0;
    }

    private int addTokens(int tokens) {
        int ts;
        while ((ts = numTokens.get()) < maxTokens) {
            int sumTs = ts + tokens;
            int nts = Math.min(maxTokens, sumTs);
            if (numTokens.compareAndSet(ts, nts))
                return sumTs - nts;
        }
        return tokens;
    }

    private int distribute(int tokens, int totalBuckets, TokenBucket[] cs) {
        int bucketsFilled;
        int excess;
        int fullBucketsMap = 0;
        int fullBuckets = 0;
        do {
            bucketsFilled = 0;
            int buckets = totalBuckets - fullBuckets;
            int tokensPerBucket = tokens / buckets;
            excess = tokens % totalBuckets;

            if (tokensPerBucket == 0)
                break;

            for (int i = 0, tbi = 0; i < cs.length && tbi < buckets; ++i) {
                TokenBucket tb = cs[i];
                if (tb != null && (fullBucketsMap & (1 << i)) == 0) {
                    int localExcess = tb.giveTokens(tokensPerBucket);
                    excess += localExcess;
                    if (localExcess == tokensPerBucket) {
                        fullBucketsMap |= 1 << i;
                        bucketsFilled += 1;
                    }
                    tbi += 1;
                }
            }

            fullBuckets += bucketsFilled;
            tokens = excess;
        } while (bucketsFilled > 0 && tokens > 0 && fullBuckets < totalBuckets);

        return excess;
    }

    /*
    private static final int PADDING = 8;

    public static void main(String[] args) {
        final long WARMUP_ITERATIONS = 100L * 1000L;
        final long ITERATIONS = 125L * 1000L * 1000L;
        final int NUM_THREADS = 4;

        executeTest(WARMUP_ITERATIONS, NUM_THREADS);

        final long start = System.nanoTime();
        long[] res = executeTest(ITERATIONS, NUM_THREADS);
        final long duration = System.nanoTime() - start;

        System.out.printf("%d threads, duration %,d (ms)\n",
                NUM_THREADS, TimeUnit.NANOSECONDS.toMillis(duration));
        int tk = 0;
        for (int i = 0; i < res.length; i += PADDING) {
            System.out.printf("token %d got %d tokens\n", tk++, res[i]);
        }
        System.out.printf("%,d ns/op\n", duration / (ITERATIONS * NUM_THREADS));
        System.out.printf("%,d ops/s\n",
                (ITERATIONS * NUM_THREADS * 1000000000L) / duration);
    }

    private static long[] executeTest(final long iterations, int numThreads) {
        final StatisticalCounter counter = new StatisticalCounter(1) {
            long value = 0;
            @Override
            public long getValue() {
                return value += 100_000l;
            }
        };
        final TokenBucketTestRate rate = new TokenBucketTestRate();
        final TokenBucket root = TokenBucket.create(1_000, rate);
        Thread[] ts = new Thread[numThreads];
        final long[] tokens = new long[numThreads * PADDING];
        final CyclicBarrier barrier = new CyclicBarrier(numThreads);

        for (int i = 0; i < ts.length; ++i) {
            final int x = i;
            ts[i] = new Thread() {
                @Override
                public void run() {
                    TokenBucket tb = root.link(100_000_000);
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        // do nothing
                    }

                    for (int i = 0; i < iterations; ++i) {
                        while (tb.tryGet(1) != 1) {
                            rate.setNewTokens(100);
                        }
                        tokens[x * 8] += 1;
                    }
                }
            };
        }

        for (Thread t : ts) {
            t.start();
        }

        for (Thread t : ts) {
            try {
                t.join();
            } catch (Exception e) {
                // do nothing
            }
        }

        return tokens;
    }
    */
}

final class RootTokenBucket extends TokenBucket {
    private final TokenBucketFillRate rate;

    RootTokenBucket(int maxTokens, TokenBucketFillRate rate) {
        super(maxTokens);
        this.rate = rate;

        numTokens.set(maxTokens);
    }

    @Override
    public int tryGet(int tokens) {
        throw new IllegalArgumentException("Can only get tokens " +
                                           "from leaf buckets");
    }

    @Override
    protected void borrowTokens() {
        int newTokens = rate.getNewTokens() + numTokens.getAndSet(0);
        if (newTokens > 0)
            giveTokens(newTokens);
    }
}

final class TokenBucketImpl extends TokenBucket {

    TokenBucketImpl(int maxTokens, TokenBucket parent) {
        super(maxTokens);
        this.parent = parent;
    }

    @Override
    public int tryGet(int tokens) {
        if (numChildren > 0)
            throw new IllegalArgumentException("Can only get tokens " +
                                               "from leaf buckets");
        int taken = tryTakeTokens(tokens);
        if (taken < tokens) {
            boolean full;
            do {
                borrowTokens();
                full = numTokens.get() == maxTokens;
                taken += tryTakeTokens(tokens - taken);
            } while (taken < tokens && full);
        }

        return taken;
    }

    private int tryTakeTokens(int tokens) {
        int ts;
        while ((ts = numTokens.get()) > 0) {
            int nts = Math.max(0, ts - tokens);
            if (numTokens.compareAndSet(ts, nts))
                return ts - nts;
        }
        return 0;
    }

    @Override
    protected void borrowTokens() {
        parent.borrowTokens();
    }
}
