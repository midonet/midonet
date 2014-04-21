/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.util;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Arrays.copyOf;

/*
 * This class implements a hierarchical token bucket. Starting from a root
 * bucket, it allows a tree of buckets to be created. Tokens go into the root
 * bucket according to the specified fill rate strategy and are distributed
 * recursively among the root's children. When all the buckets in a level of
 * the hierarchy are full, they accumulate in the parent bucket. Tokens can
 * only be retrieved from the leaf buckets, although you can wait for any
 * bucket to contain a specified number of tokens. The operation of the token
 * bucket is as follows:
 *
 *                  +–––––+
 *                  |     | root
 *                  |     |
 *                  +––+––+
 *                     |
 *             +–––––––+––––––+
 *             |              |
 *          +––+––+        +––+––+
 *  middle0 |     |        |     | middle1
 *          |     |        |     |
 *          +–––––+        +––+––+
 *                            |
 *                    +–––––––+–––––––+
 *                    |               |
 *                 +––+––+         +––+––+
 *           leaf0 |     |         |     | leaf1
 *                 |     |         |     |
 *                 +–––––+         +–––––+
 *
 * - When retrieving tokens from a leaf, we first look to see if the it
 *   contains the specified amount of tokens, and consume those tokens locally;
 * - If it does not, then we trigger a distribution of tokens. This entails
 *   going up to the root, getting the new tokens from the TokenBucketFillRate™
 *   and, together with any previously accumulated tokens, distribute them
 *   recursively. We evenly distribute tokens between the two middle buckets.
 * - In middle0, we just add the tokens to its current account and return the
 *   excess tokens over its allowed maximum. For middle1, we distribute evenly
 *   to the leafs and accumulate excess tokens in middle1. Again, if all the
 *   leafs and also middle1 are full, we return to the root the excess tokens.
 * - We do a fair distribution, meaning that, for this particular case, if the
 *   number of tokens is odd, than we always accumulate at least one token at
 *   middle1 or at the root.
 * - Again at the leaf, we try again to consume local tokens, assuming we
 *   distributed new tokens. Note that we may have depleted the bucket and the
 *   distribution may have filled it up again, meaning that now we got two full
 *   bursts. This is okay, as it only happens when all the other buckets are full.
 * - If we still don't have enough tokens, we are going up the hierarchy again
 *   consuming all excess tokens accumulated in the middle or root buckets. In
 *   this case there is no fairness and two buckets may compete for these tokens.
 * - We execute a fixed point algorithm, meaning that we loop around until we
 *   obtained all the requested tokens or until we are unable to distribute new
 *   tokens and grab excess ones.
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
            protected int grabExcess(int numTokens) {
                return numTokens;
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
                    numChildren -= 1;
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public final int getMaxTokens() {
        return maxTokens;
    }

    public final void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        int ts;
        do {
            ts = numTokens.get();
        } while (ts > maxTokens && !numTokens.compareAndSet(ts, maxTokens));
    }

    public final int getNumTokens() {
        return numTokens.get();
    }

    public int tryGet(int tokens) {
        if (numChildren > 0)
            throw new IllegalArgumentException("Can only get tokens " +
                    "from leaf buckets");
        int remaining = tokens - tryTakeTokens(tokens);
        int oldRemaining = -1;
        while (remaining > 0 && remaining != oldRemaining) {
            oldRemaining = remaining;
            parent.distribute();
            remaining -= tryTakeTokens(remaining);
            if (remaining > 0)
                remaining -= parent.grabExcess(remaining);
        }

        return tokens - remaining;
    }

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

                tb.distribute();
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
    protected void distribute() {
        parent.distribute();
    }

    private int giveTokens(final int tokens) {
        if (numChildren > 0) {
            int excess = doDistribution(tokens, numChildren, children) +
                         numTokens.getAndSet(0);
            return excess == 0 ? 0 : addTokens(excess);
        }

        return addTokens(tokens);
    }


    protected int grabExcess(int numTokens) {
        int remaining = numTokens - tryTakeTokens(numTokens);
        if (remaining > 0 && parent != null)
            remaining -= parent.grabExcess(remaining);
        return numTokens - remaining;
    }

    protected final int addTokens(int tokens) {
        int ts;
        while ((ts = numTokens.get()) < maxTokens) {
            int sumTs = ts + tokens;
            int nts = Math.min(maxTokens, sumTs);
            if (numTokens.compareAndSet(ts, nts))
                return sumTs - nts;
        }
        return tokens;
    }

    protected final int tryTakeTokens(int tokens) {
        int ts;
        while ((ts = numTokens.get()) > 0) {
            int nts = Math.max(0, ts - tokens);
            if (numTokens.compareAndSet(ts, nts))
                return ts - nts;
        }
        return 0;
    }

    protected final int doDistribution(int tokens, int totalBuckets,
                                       TokenBucket[] cs) {
        int excess = 0;
        int fullBucketsMap = 0;
        int fullBuckets = 0;
        int previousTokens = 0;
        while (previousTokens != tokens && fullBuckets < totalBuckets) {
            previousTokens = tokens;
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
                        fullBuckets += 1;
                    }
                    tbi += 1;
                }
            }

            tokens = excess;
        }

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
    protected void distribute() {
        int newTokens = rate.getNewTokens() + numTokens.getAndSet(0);
        if (newTokens > 0) {
            int excess = doDistribution(newTokens, numChildren, children);
            if (excess > 0)
                addTokens(excess);
        }
    }
}

final class TokenBucketImpl extends TokenBucket {

    TokenBucketImpl(int maxTokens, TokenBucket parent) {
        super(maxTokens);
        this.parent = parent;
    }
}
