/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.midolman.state;

import java.util.concurrent.atomic.AtomicReference;

import com.google.inject.Inject;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.SystemDataProvider;

/**
 * This class is the zookeeper data access class for system data info.
 */
public class ZkSystemDataProvider implements SystemDataProvider {

    private final static Logger log =
        LoggerFactory.getLogger(ZkSystemDataProvider.class);

    private ZkManager zk;
    private PathBuilder paths;
    private AtomicReference<String> cachedWriteVersion
        = new AtomicReference<>(null);

    private Runnable writeVersionWatcher = new WriteVersionWatcher();

    @Inject
    public ZkSystemDataProvider(ZkManager zk, PathBuilder paths) {
        this.zk = zk;
        this.paths = paths;
    }

    /**
     * Get the current write version in a plain string.
     *
     * @return  The current write version
     * @throws StateAccessException
     */
    @Override
    public String getWriteVersion() throws StateAccessException {
        log.trace("Entered ZkSystemDataProvider.getWriteVersion");
        String version = cachedWriteVersion.get();
        if (version == null) {
            byte[] data = zk.get(paths.getWriteVersionPath(),
                                 writeVersionWatcher);
            if (data != null) {
                version = new String(data);
                cachedWriteVersion.set(version);
            }
        }
        log.trace("Exiting ZkSystemDataProvider.getWriteVersion. " +
                "Version={}", version);
        return version;
    }

    class WriteVersionWatcher extends Directory.DefaultTypedWatcher
        implements Runnable, DirectoryCallback<byte[]> {
        final int DEFAULT_RETRIES = 10;
        int retries = DEFAULT_RETRIES;

        public void onSuccess(byte[] data) {
            cachedWriteVersion.set(new String(data));
        }

        public void onTimeout() {
            log.error("Timeout reading from zookeeper, trying again");
            readWriteVersion();
        }

        public void onError(KeeperException e) {
            log.error("Error reading from zookeeper, trying again", e);
            readWriteVersion();
        }

        public void readWriteVersion() {
            if (--retries > 0) {
                zk.asyncGet(paths.getWriteVersionPath(),
                            this, this);
            } else {
                cachedWriteVersion.set(null);
            }
        }

        public void run() {
            readWriteVersion();
        }
    }
}
