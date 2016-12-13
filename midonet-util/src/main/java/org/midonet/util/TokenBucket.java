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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Arrays.copyOf;

/**
 * This class implements a hierarchical token bucket. Starting from a root
 * bucket, it allows a tree of buckets to be created. Tokens go into the root
 * bucket according to the specified fill rate strategy and are distributed
 * recursively among the root's children. When all the buckets in a level of
 * the hierarchy are full, they accumulate in the parent bucket. Tokens can
 * only be retrieved from the leaf buckets.
 */
public class TokenBucket {
    public static final int UNLINKED = -1;

    static final Logger log = LoggerFactory.getLogger("org.midonet.io.htb");
    static final boolean isTraceEnabled = log.isTraceEnabled();

    final PaddedAtomicInteger numTokens = new PaddedAtomicInteger();
    protected final String name;
    private final RootTokenBucket root;
    private final TokenBucket parent;

    protected int capacity;
    private int numChildren;
    protected TokenBucket[] children = new TokenBucket[0];
    /* As we ensure the size of the children array is always a multiple of 2,
     * we can use the bitwise & operator to implement the modulo operation
     * instead of the much more expensive % operator. We do that by doing a
     * bitwise & between the index and the mask.f
     */
    private int mask;

    /* To amortize the costs of having multiple distribution iterations, we keep
     * this distribution-private counter at each bucket where we accumulate this
     * bucket's reserved tokens. At the end of the distribution, we atomically
     * add those reserved tokens to the running, thread-safe capacity counter.
     */
    private int reservedTokens;
    // This field enables round-robin distributions, needed for fairness
    private int distributionIndex;

    TokenBucket(int capacity, String name, TokenBucket parent) {
        this.capacity = capacity;
        this.name = name;
        this.parent = parent;
        TokenBucket tb;
        for (tb = this; tb.parent != null; tb = tb.parent) ;
        root = (RootTokenBucket)tb;
    }

    /* Creates a new TokenBucket root to which other buckets can be linked.
     */
    public static TokenBucket create(int capacity,
                                     String name,
                                     TokenBucketFillRate rate) {
        return new RootTokenBucket(capacity, name, rate);
    }

    public void dumpToLog() {
        log.info("name:{} cap:{} tokens:{}", name, capacity, numTokens.get());

        for (TokenBucket child : children) {
            if (child != null)
                child.dumpToLog();
        }
    }

    /* Creates a new TokenBucket and links it as a child of this bucket.
     */
    public final TokenBucket link(int capacity, String name) {
        root.lock();
        try {
            String n = this.name + "/" + name;
            TokenBucket ntb = new TokenBucket(capacity, n, this);
            int idx = findFreeIndex();
            if (idx < 0) {
                idx = children.length;
                growArray();
            }

            children[idx] = ntb;
            numChildren += 1;

            return ntb;
        } finally {
            root.unlock();
            root.tryDistribute();
        }
    }

    /* Unlinks this TokenBucket from its parent and returns the amount of
     * tokens that it held.
     */
    public final int unlink() {
        root.lock();
        try {
            if (isUnlinked() || parent == null)
                return 0;

            TokenBucket[] siblings = parent.children;
            for (int i = 0; i < siblings.length; ++i) {
                if (siblings[i] == this) {
                    siblings[i] = null;
                    parent.numChildren -= 1;
                    return numTokens.getAndSet(UNLINKED);
                }
            }

            return 0;
        } finally {
            root.unlock();
            root.tryDistribute();
        }
    }

    public final int getCapacity() {
        return capacity;
    }

    public final void setCapacity(int capacity) {
        root.lock();
        try {
            this.capacity = capacity;
            int ts;
            do {
                ts = numTokens.get();
            } while (ts > capacity && !numTokens.compareAndSet(ts, capacity));
        } finally {
            root.unlock();
            root.tryDistribute();
        }
    }

    public final String getName() {
        return name;
    }

    public final TokenBucket getParent() {
        return parent;
    }

    public final int getNumTokens() {
        return numTokens.get();
    }

    /* Tries to get the specified amount of tokens from this leaf bucket. It
     * returns the amount of tokens actually retrieved in the closed interval
     * [0, tokens]. The method is thread-safe and wait-free.
     */
    public int tryGet(int tokens) {
        if (numChildren > 0)
            throw new IllegalArgumentException("Can only get tokens " +
                                               "from leaf buckets");

        int remaining = tokens - tryTakeTokens(tokens);
        int oldRemaining = -1;
        while (remaining > 0 && remaining != oldRemaining) {
            oldRemaining = remaining;
            root.tryDistribute();
            remaining -= tryTakeTokens(remaining);
        }

        int acquired = tokens - remaining;
        if (isTraceEnabled) {
            log.trace("[{}|{}] got {}/{} tokens",
                      Thread.currentThread().getId(), name, acquired, tokens);
        }
        return acquired;
    }

    /* Tries to add the specified amount of tokens, returning the excess
     * amount of tokens that would go over the bucket's capacity.
     */
    public final int addTokens(int tokens) {
        int ts;
        while ((ts = numTokens.get()) < capacity && ts != UNLINKED) {
            int sumTs = ts + tokens;
            int nts = Math.min(capacity, sumTs);
            if (numTokens.compareAndSet(ts, nts))
                return sumTs - nts;
        }
        return tokens;
    }

    private void growArray() {
        children = copyOf(children, Math.max(4, children.length << 1));
        mask = children.length - 1;
    }

    private int findFreeIndex() {
        for (int i = 0; i < children.length; ++i) {
            if (children[i] == null)
                return i;
        }
        return -1;
    }

    private boolean isUnlinked() {
        return numTokens.get() == UNLINKED;
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

    /* This method performs a distribution of the specified amount of tokens
     * among this bucket's children. It returns any excess tokens that couldn't
     * be distributed.
     */
    final int doDistribution(int tokens) {
        boolean hasNonFullChildren;
        do {
            /* Give tokens to each non-full bucket. Note that a full bucket
             * may concurrently become non-full.
             */
            hasNonFullChildren = false;
            for (int i = 0; i < children.length; ++i) {
                TokenBucket tb = children[(distributionIndex + i) & mask];
                if (tb != null && tb.reserve()) {
                    hasNonFullChildren = true;
                    if ((tokens -= 1) == 0) {
                        distributionIndex += i + 1;
                        return 0;
                    }
                }
            }
        } while (hasNonFullChildren);
        return tokens;
    }

    /* This method adds one token to this subtree, which becomes reserved and
     * cannot be consumed by any of its leaf buckets. If the bucket has children
     * then a distribution is triggered. The tokens that couldn't be distributed
     * are reserved in this bucket. The method returns false if the new token
     * couldn't be reserved due to the bucket's capacity. After the reservation
     * stage, the tokens are made effective at end of the outermost distribution
     * so we can save atomic instructions.
     */
    private boolean reserve() {
        if (numChildren > 0) {
            int tokens = 1 + getAndClearAccumulatedTokens() +
                         getAndClearReservedTokens();
            int toAccumulate = doDistribution(tokens);

            // We can only go over capacity by one
            if (toAccumulate > capacity) {
                reservedTokens = capacity;
                return false;
            }
            reservedTokens = toAccumulate;
            return true;
        }

        if (reservedTokens + numTokens.get() == capacity)
            return false;
        reservedTokens += 1;
        return true;
    }

    /* This method adds the reserved tokens to the running token counter.
     * As only one distribution happens at a time, we are guaranteed there
     * will be space for the reserved tokens. We are also guaranteed buckets
     * aren't concurrently unlinked.
     */
    final void applyReserved() {
        if (isTraceEnabled) {
            log.trace("[{}|{}] got {} new tokens",
                    Thread.currentThread().getId(), name, reservedTokens);
        }

        if (reservedTokens > 0) {
            if (numChildren > 0) {
                // This is called under the distribution lock, so we don't need
                // atomic operations on numTokens.
                numTokens.set(reservedTokens);
            }
            else {
                numTokens.addAndGet(reservedTokens);
            }

            reservedTokens = 0;
        }

        for (TokenBucket tb : children) {
            if (tb != null)
                tb.applyReserved();
        }
    }

    private int getAndClearAccumulatedTokens() {
        // This is called under the distribution lock, so we don't need
        // atomic operations on numTokens.
        int tokens = numTokens.get();
        if (tokens > 0)
            numTokens.set(0);
        return tokens;
    }

    private int getAndClearReservedTokens() {
        int tokens = reservedTokens;
        reservedTokens = 0;
        return tokens;
    }

    /*
    private static final int PADDING = 8;

    public static void main(String[] args) {
        final int WARMUP_ITERATIONS = 100 * 1000;
        final int ITERATIONS = 250 * 1000 * 1000;
        final int NUM_THREADS = 4;

        executeTest(WARMUP_ITERATIONS, NUM_THREADS);

        final long start = System.nanoTime();
        long[] res = executeTest(ITERATIONS, NUM_THREADS);
        final long duration = System.nanoTime() - start;

        System.out.printf("%d threads, duration %,d (ms)\n",
                NUM_THREADS, TimeUnit.NANOSECONDS.toMillis(duration));
        int b = 0;
        for (int i = 0; i < res.length; i += PADDING) {
            System.out.printf("bucket %d got %d tokens\n", b++, res[i]);
        }
        System.out.printf("%,d ns/op\n", duration / (ITERATIONS * NUM_THREADS));
        System.out.printf("%,d ops/s\n",
                (ITERATIONS * NUM_THREADS * 1000000000L) / duration);
    }

    private static long[] executeTest(final int requests, int numThreads) {
        final StatisticalCounter c = new StatisticalCounter(numThreads);
        final int tokenMultiplier = 8;
        final TokenBucketFillRate rate = new TokenBucketSystemRate(c, tokenMultiplier);
        int capacity = requests / tokenMultiplier;
        final TokenBucket root = TokenBucket.create(capacity, "test-root", rate);
        root.addTokens(capacity);
        Thread[] ts = new Thread[numThreads];
        final long[] tokens = new long[numThreads * PADDING];
        final CyclicBarrier barrier = new CyclicBarrier(numThreads);

        for (int i = 0; i < ts.length; ++i) {
            final int x = i;
            ts[i] = new Thread() {
                @Override
                public void run() {
                    TokenBucket tb = root.link(100, "test-" + x);
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        // do nothing
                    }

                    for (int i = 0; i < requests / tokenMultiplier; ++i) {
                        while (tb.tryGet(1) != 1) ;
                        for (int j = 0; j < tokenMultiplier; ++j) {
                            tokens[x * 8] += 1;
                            c.addAndGet(x, 1);
                        }
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
    /* We use an atomic integer to lock the distribution. We don't use a
     * ReentrantLock because we want the lock word to be on its own cache line
     * and because we take advantage of weaker consistency on unlock. We only
     * require a store-store barrier to ensure that a thread that sees the lock
     * unlocked will also see the excess tokens stored in numTokens. We don't
     * require a stronger volatile-store or a full fence (CAS) that would flush
     * the CPU's store buffers.
     */
    private final PaddedAtomicInteger distributionInProgress;

    RootTokenBucket(int capacity, String name, TokenBucketFillRate rate) {
        super(capacity, name, null);
        this.rate = rate;
        distributionInProgress = new PaddedAtomicInteger();
    }

    /* Triggers a distribution. A distribution is performed under a lock,
     * ensuring that only one distribution is happening at any given time.
     */
    final void tryDistribute() {
        if (tryLock()) {
            int newTokens = rate.getNewTokens() + numTokens.get();
            if (newTokens > 0) {
                if (isTraceEnabled) {
                    log.trace("[{}|{}] distributing {} new tokens",
                              Thread.currentThread().getId(), name, newTokens);
                }

                int excess = doDistribution(newTokens);
                numTokens.set(Math.min(excess, capacity));

                for (TokenBucket tb : children) {
                    if (tb != null)
                        tb.applyReserved();
                }

                if (isTraceEnabled) {
                    log.trace("[{}|{}] finished distribution with {} excess tokens",
                              Thread.currentThread().getId(), name, excess);
                }
            }

            unlock();
        } else {
            // Distribution should be very fast, so we spin for a while
            // waiting for it to be done before we try to grab more tokens.
            // TODO: When we apply back-pressure in the kernel, packets
            // won't be read unless we have tokens, so we can just fail here
            // and let the thread go process other channels. It will come back
            // to this channel and it will hopefully have tokens so the packets
            // aren't dropped.
            for (int retries = 0; retries < 100; ++retries) ;
        }
    }

    /* Prevents distributions from happening. Note that concurrent tryGet
     * operations are still possible.
     */
    final void lock() {
        while (!tryLock())
            Thread.yield();
    }

    final boolean tryLock() {
        return distributionInProgress.compareAndSet(0, 1);
    }

    final void unlock() {
        distributionInProgress.lazySet(0);
    }
}
