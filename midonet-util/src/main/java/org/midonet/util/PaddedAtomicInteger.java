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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class wraps an AtomicInteger, padding the cache line it is on. This
 * ensures that there will be no false sharing when multiple threads are
 * accessing different, but adjacent fields. We assume 64 byte cache lines,
 * so we pad the 4 byte integer with 48 additional bytes plus the 8 byte-aliged
 * header of 8/16 bytes.
 */
public class PaddedAtomicInteger extends AtomicInteger {

    private static final long serialVersionUID = 1L;

    public PaddedAtomicInteger() {
    }

    public PaddedAtomicInteger(final int initialValue) {
        super(initialValue);
    }

    public volatile long p1, p2, p3, p4, p5, p6 = 7;
}
