/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.state;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

import com.midokura.util.functors.Callback;

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
}