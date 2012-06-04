// Copyright 2011 Midokura Inc.

package com.midokura.midolman.openvswitch

import com.midokura.util.lock.LockHelper

// Mutex the OVSDB Connection, using a filesystem lock
object OpenvSwitchDatabaseConnectionMutexer {
    private final val lock = LockHelper.createLock("ovsdbconnection")

    def takeLock() { lock.lock() }

    def releaseLock() { lock.release() }
}

class OpenvSwitchDatabaseConnectionMutexer {}
