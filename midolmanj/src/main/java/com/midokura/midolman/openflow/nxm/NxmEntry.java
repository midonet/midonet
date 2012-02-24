/*
 * Copyright 2012 Midokura KK
 */

package com.midokura.midolman.openflow.nxm;

interface NxmEntry {
    NxmRawEntry createRawNxmEntry();
    NxmEntry fromRawEntry(NxmRawEntry rawEntry);
    NxmType getNxmType();
}
