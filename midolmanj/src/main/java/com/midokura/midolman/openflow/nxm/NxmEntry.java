/*
 * @(#)NxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

interface NxmEntry {
    NxmRawEntry createRawNxmEntry();
    NxmEntry fromRawEntry(NxmRawEntry rawEntry);
    NxmType getNxmType();
}
