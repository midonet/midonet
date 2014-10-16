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
package org.midonet.midolman.state.zkManagers;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

public class PortSetZkManager extends BaseZkManager {

    private static final Logger log = LoggerFactory
        .getLogger(PortSetZkManager.class);


    /**
     * Initializes a PortGroupZkManager object with a ZooKeeper client and the
     * root path of the ZooKeeper directory.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public PortSetZkManager(ZkManager zk, PathBuilder paths,
                            Serializer serializer) {
        super(zk, paths, serializer);
    }

    public void getPortSetAsync(UUID portSetId,
                                final DirectoryCallback<Set<UUID>>
                                        portSetContentsCallback,
                                Directory.TypedWatcher watcher) {
        getUUIDSetAsync(paths.getPortSetPath(portSetId),
                portSetContentsCallback, watcher);
    }

    public Set<UUID> getPortSet(UUID portSetId, Directory.TypedWatcher watcher)
            throws StateAccessException {
        return getUuidSet(paths.getPortSetPath(portSetId));
    }

    public void addMemberAsync(UUID portSetId, UUID memberId,
                               DirectoryCallback.Add cb) {
        String portSetPath =
            paths.getPortSetEntryPath(portSetId, memberId);
        zk.ensureEphemeralAsync(portSetPath, null, cb);
    }

    public void addMember(UUID portSetId, UUID memberId)
        throws StateAccessException {

        String memberEntryPath =
            paths.getPortSetEntryPath(portSetId, memberId);
        zk.ensureEphemeral(memberEntryPath, null);
    }

    public void delMemberAsync(UUID portSetId, UUID entryId,
                               DirectoryCallback.Void callback) {
        String portSetPath = paths.getPortSetEntryPath(portSetId, entryId);
        zk.asyncDelete(portSetPath, callback);
    }

    public void delMember(UUID portSetId, UUID memberID)
        throws StateAccessException {
        String portSetPath =
            paths.getPortSetEntryPath(portSetId, memberID);
        zk.delete(portSetPath);
    }
}
