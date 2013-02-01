/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.state;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;

import org.midonet.util.functors.Callback;

public interface DirectoryCallback<T>
    extends Callback<DirectoryCallback.Result<T>, KeeperException> {

    public static class Result<T> {
        T data;
        Stat stat;

        public Result(T data, Stat stat) {
            this.data = data;
            this.stat = stat;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }

        public Stat getStat() {
            return stat;
        }

        public void setStat(Stat stat) {
            this.stat = stat;
        }
    }

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
