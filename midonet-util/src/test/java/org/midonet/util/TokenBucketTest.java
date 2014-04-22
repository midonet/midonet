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
        TokenBucket root = TokenBucket.create(10, "test-root", tr);
        tr.setNewTokens(10);
        TokenBucket tb1 = root.link(5, "tb1"),
                    tb2 = root.link(5, "tb2");

        assertThat(tb1.tryGet(10), is(5));
        tr.setNewTokens(10);
        assertThat(tb1.tryGet(10), is(10));
    }

    @Test
    public void testMultiLevels() {
        TokenBucketTestRate tr = new TokenBucketTestRate();
        TokenBucket root = TokenBucket.create(20, "test-root", tr);
        tr.setNewTokens(20);
        TokenBucket middle0 = root.link(0, "middle0"),
                    middle1 = root.link(5, "middle1");

        TokenBucket leaf0 = middle0.link(5, "leaf0"),
                    leaf1 = middle0.link(5, "leaf1");

        assertThat(leaf0.tryGet(10), is(10));
        assertThat(middle1.tryGet(5), is(5));
        assertThat(leaf1.tryGet(5), is(5));
    }

    @Test
    public void testTokenBucketSystemRate() {
        StatisticalCounter c = new StatisticalCounter(1);
        TokenBucketSystemRate r = new TokenBucketSystemRate(c);

        assertThat(r.getNewTokens(), is(0));

        c.addAndGet(0, 3);
        assertThat(r.getNewTokens(), is(3));

        c.addAndGet(0, 2);
        assertThat(r.getNewTokens(), is(2));
    }

    class HTBTree {
        TokenBucketTestRate r = new TokenBucketTestRate();
        TokenBucket root = TokenBucket.create(16, "test-root", r);
        TokenBucket tunnel = root.link(8, "tunnel"),
                    vms    = root.link(0, "vms");

        TokenBucket vm0 = vms.link(4, "vm0"),
                    vm1 = vms.link(4, "vm1");


        public void reset(int root, int tunnel, int vm0, int vm1) {
            this.r.setNewTokens(root);
            this.tunnel.addTokens(tunnel);
            this.vm0.addTokens(vm0);
            this.vm1.addTokens(vm1);
        }
    }

    @Test
    public void testExcess() {
        HTBTree htb = new HTBTree();
        htb.reset(4, 4, 3, 3);

        assertThat(htb.vm0.tryGet(4), is(4));
        assertThat(htb.vm1.tryGet(4), is(4));
        assertThat(htb.tunnel.tryGet(8), is(6));
    }

    @Test
    public void testExcess2() {
        HTBTree htb = new HTBTree();
        htb.reset(6, 7, 2, 2);

        assertThat(htb.vm0.tryGet(4), is(4));
        assertThat(htb.vm1.tryGet(4), is(4));
        assertThat(htb.tunnel.tryGet(8), is(8));
        assertThat(htb.root.getNumTokens(), is(0));
        assertThat(htb.vm0.tryGet(1), is(1));
    }

    @Test
    public void testAllFullButOneLeaf() {
        HTBTree htb = new HTBTree();
        htb.reset(4, 8, 4, 3);

        assertThat(htb.vm0.tryGet(4), is(4));
        assertThat(htb.vm1.tryGet(4), is(4));
        assertThat(htb.tunnel.tryGet(8), is(8));
    }

    @Test
    public void testNoMinimumAllotment() {
        HTBTree htb = new HTBTree();
        htb.reset(3, 0, 0, 0);

        assertThat(htb.vm0.tryGet(1), is(0));
        assertThat(htb.vm1.tryGet(1), is(0));
        assertThat(htb.tunnel.tryGet(1), is(0));
        assertThat(htb.root.getNumTokens(), is(3));

        htb.root.addTokens(2);

        assertThat(htb.vm0.tryGet(4), is(1));
        assertThat(htb.vm1.tryGet(4), is(1));
        assertThat(htb.tunnel.tryGet(4), is(2));
        assertThat(htb.root.getNumTokens(), is(1));
    }

    @Test
    public void testAllotmentRecalculation() {
        HTBTree htb = new HTBTree();

        TokenBucket tmp = htb.vms.link(4, "tmp");
        assertThat(htb.root.distributabilityAllotment, is(6));

        TokenBucket tmp2 = htb.vms.link(4, "tmp2");
        assertThat(htb.root.distributabilityAllotment, is(8));

        htb.vms.unlink(tmp2);
        assertThat(htb.root.distributabilityAllotment, is(6));

        htb.vms.unlink(tmp);
        assertThat(htb.root.distributabilityAllotment, is(4));

        htb.root.unlink(htb.tunnel);
        assertThat(htb.root.distributabilityAllotment, is(2));
    }
}
