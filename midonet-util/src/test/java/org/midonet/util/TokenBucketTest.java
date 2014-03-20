/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.util;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class TokenBucketTest {

    @Test
    public void testRepeatedBurst() {
        TokenBucketTestRate tr = new TokenBucketTestRate();
        TokenBucket root = TokenBucket.create(10, tr);
        TokenBucket tb1 = root.link(5),
                    tb2 = root.link(5);

        assertThat(tb1.tryGet(10), is(5));
        tr.setNewTokens(10);
        assertThat(tb1.tryGet(10), is(10));
    }

    @Test
    public void testMultiLevels() {
        TokenBucketTestRate tr = new TokenBucketTestRate();
        TokenBucket root = TokenBucket.create(20, tr);
        TokenBucket middle0 = root.link(0),
                    middle1 = root.link(5);

        TokenBucket leaf0 = middle0.link(5),
                    leaf1 = middle0.link(5);

        assertThat(leaf0.tryGet(10), is(10));
        assertThat(middle1.tryGet(5), is(5));
        assertThat(leaf1.tryGet(5), is(5));
    }

    @Test
    public void testDecreaseMaxTokens() {
        TokenBucket root = TokenBucket.create(10, new TokenBucketTestRate());
        root.setMaxTokens(5);

        assertThat(root.getMaxTokens(), is(5));
        assertThat(root.getNumTokens(), is(5));
    }
}
