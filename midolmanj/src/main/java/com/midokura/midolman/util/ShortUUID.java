package com.midokura.midolman.util;

import java.util.Random;
import java.util.UUID;

public class ShortUUID {

    private static Random rand = new Random();

    public static UUID generate32BitUUID() {
        // TODO: make this non-static and use ZK to generate sequence numbers.
        int r = rand.nextInt();
        return new UUID(0, (long) r);
    }

    public static int UUID32toInt(UUID id) {
        long lBits = id.getLeastSignificantBits();
        if (0 != id.getMostSignificantBits() || lBits < Integer.MIN_VALUE
                || lBits > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "uuid cannot be converted to int without losing information.");
        }
        return (int) lBits;
    }

    public static UUID intTo32BitUUID(int id) {
        return new UUID(0, (long) id);
    }

}
