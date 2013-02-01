/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.util.lock;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Lock Helper class to allow easy use of file system file locks.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 6/4/12
 */
public class LockHelper {

    private static final Logger log = LoggerFactory
        .getLogger(LockHelper.class);

    public static final String WAIT_LOCK_FOR_FUNCTIONAL_TESTS =
        "midonet.functional-tests.wait-for-lock";

    public static Lock lock(String key) {
        Lock lock = new Lock(key);

        boolean failFast = Boolean.getBoolean(WAIT_LOCK_FOR_FUNCTIONAL_TESTS);

        if (failFast) {
            boolean isLockAcquired = lock.tryLock();
            assertThat("We couldn't acquire the functional tests lock",
                       isLockAcquired, is(true));
        } else {
            lock.lock();
        }

        return lock;
    }

    public static Lock createLock(String key) {
        return new Lock(key);
    }

    public static class Lock {
        FileLock lock;
        private final File file;

        private Lock(String key) {
            file = new File(String.format("/tmp/midonet-%s.lock", key));
        }

        @SuppressWarnings("ResultOfMethodCallIgnored")
        public void lock() {
            try {
                file.setReadable(true, false);
                file.setWritable(true, false);
                lock = new RandomAccessFile(file, "rw").getChannel().lock();
            } catch (IOException ex) {
                throw
                    new RuntimeException("Could not lock on file: "
                                             + file.getAbsolutePath(), ex);
            }
        }

        @SuppressWarnings("ResultOfMethodCallIgnored")
        public boolean tryLock() {
            try {
                file.setReadable(true, false);
                file.setWritable(true, false);
                lock = new RandomAccessFile(file, "rw").getChannel().tryLock();
            } catch (OverlappingFileLockException ex) {
                return false;
            } catch (ClosedChannelException ex) {
                throw
                    new RuntimeException("Could not lock on filesystem file "
                                             + file.getAbsolutePath(), ex);
            } catch (IOException ex) {
                throw
                    new RuntimeException("Could not lock on filesystem file "
                                             + file.getAbsolutePath(), ex);
            }

            return lock != null;
        }

        public void release() {

            try {
                if (lock != null) {
                    lock.release();
                }
            } catch (IOException e) {
                log.error("Exception while releasing a file system lock.", e);
            }
        }
    }
}
