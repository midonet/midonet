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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import org.midonet.cluster.data.storage.Directory;

public class DeviceToGreKeyMap {

    private Directory dir;

    public DeviceToGreKeyMap(Directory dir) {
        this.dir = dir;
    }

    public void add(UUID deviceId, int gre_key) throws IOException,
            KeeperException, InterruptedException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeInt(gre_key);
        out.close();
        dir.add("/" + deviceId.toString(), bos.toByteArray(),
                CreateMode.PERSISTENT);
    }

    public boolean exists(UUID deviceId) throws KeeperException, InterruptedException {
        return dir.has("/" + deviceId.toString());
    }

    public void setGreKey(UUID deviceId, int gre_key) throws IOException,
            KeeperException, InterruptedException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeInt(gre_key);
        out.close();
        dir.update("/" + deviceId.toString(), bos.toByteArray());
    }

    public int getGreKey(UUID deviceId) throws IOException, KeeperException,
            InterruptedException {
        byte[] data = dir.get("/" + deviceId.toString(), null);
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        ObjectInputStream in = new ObjectInputStream(bis);
        int greKey = in.readInt();
        in.close();
        return greKey;
    }

    public void delete(UUID deviceId) throws KeeperException,
            InterruptedException {
        dir.delete("/" + deviceId.toString());
    }
}
