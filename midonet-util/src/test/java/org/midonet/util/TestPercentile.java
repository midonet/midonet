package org.midonet.util;

import org.junit.Assert;
import org.junit.Test;

public class TestPercentile {

    @Test
    public void test() {
        Percentile p = new Percentile();

        for ( int i = 0; i < 100; ++i ) {
            p.sample(i);
        }

        Assert.assertEquals(36.0, p.getPercentile(0.6), 0.0);
    }

}
