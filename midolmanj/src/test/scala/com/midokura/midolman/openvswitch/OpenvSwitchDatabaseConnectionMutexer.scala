// Copyright 2011 Midokura Inc.

package com.midokura.midolman.openvswitch

import java.io.{File, RandomAccessFile}
import java.nio.channels.FileLock

// Mutex the OVSDB Connection, using a mode 666 lockfile in /tmp
object OpenvSwitchDatabaseConnectionMutexer {
    private final var lockfile = new File("/tmp/ovsdbconnection.lock")
    private var lock: FileLock = _

    def takeLock() = {
        lockfile.setReadable(true, false)
        lockfile.setWritable(true, false)
        lock = new RandomAccessFile(lockfile, "rw").getChannel.lock
    }

    def releaseLock() { lock.release }
}
class OpenvSwitchDatabaseConnectionMutexer {}
