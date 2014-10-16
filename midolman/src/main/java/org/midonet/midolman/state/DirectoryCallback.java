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
package org.midonet.midolman.state;

import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;

import org.midonet.util.functors.Callback;

public interface DirectoryCallback<T> extends Callback<T, KeeperException> {

    public static interface Void extends DirectoryCallback<java.lang.Void> {

    }

    public static interface Add extends DirectoryCallback<String> {

    }

    public abstract class DirectoryCallbackLogErrorAndTimeout<T> implements DirectoryCallback<T>{

        String itemInfo;
        Logger log;

        protected DirectoryCallbackLogErrorAndTimeout(String logInfo, Logger log) {
            this.itemInfo = logInfo;
            this.log = log;
        }

        @Override
        public void onTimeout() {
            log.error("TimeOut during async operation - {}", itemInfo);
        }

        @Override
        public void onError(KeeperException e) {
            log.error("Exception when trying to async operation - {}", itemInfo);
        }
    }
}
