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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.DirectoryCallbackFactory;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.util.functors.Functor;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.cluster.data.BGP;

public class BgpZkManager extends AbstractZkManager<UUID, BGP.Data> {

    AdRouteZkManager adRouteZkManager;

    /**
     * BgpZkManager constructor.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public BgpZkManager(ZkManager zk, PathBuilder paths,
                        Serializer serializer) {
        super(zk, paths, serializer);
        adRouteZkManager = new AdRouteZkManager(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getBgpPath(id);
    }

    @Override
    protected Class<BGP.Data> getConfigClass() {
        return BGP.Data.class;
    }

    public List<Op> prepareCreate(UUID id, BGP.Data config)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>(3);

        ops.add(simpleCreateOp(id, config));
        ops.add(Op.create(paths.getBgpAdRoutesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getPortBgpPath(config.portId, id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        return ops;
    }

    private List<Op> prepareDelete(UUID id) throws StateAccessException,
            SerializationException {
        return prepareDelete(id, get(id));
    }

    private List<Op> prepareDelete(UUID id, BGP.Data config)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();

        // Delete the advertising routes
        List<UUID> adRouteIds = adRouteZkManager.list(id);
        for (UUID adRouteId : adRouteIds) {
            ops.addAll(adRouteZkManager.prepareDelete(adRouteId));
        }
        ops.add(Op.delete(paths.getBgpAdRoutesPath(id), -1));

        // Delete the port bgp entry
        ops.add(Op.delete(paths.getPortBgpPath(config.portId, id), -1));

        // Delete the bgp
        ops.add(Op.delete(paths.getBgpPath(id), -1));
        return ops;
    }

    public List<Op> preparePortDelete(UUID portId) throws StateAccessException,
            SerializationException {
        List<Op> ops = new ArrayList<>();

        List<UUID> bgpIdList = list(portId);
        for (UUID bgpId : bgpIdList) {
            ops.addAll(prepareDelete(bgpId));
        }

        return ops;
    }

    public UUID create(BGP bgp) throws StateAccessException,
            SerializationException {
        UUID id = UUID.randomUUID();
        zk.multi(prepareCreate(id, bgp.getData()));
        return id;
    }

    public void getBGPAsync(final UUID bgpId, DirectoryCallback <BGP> bgpDirectoryCallback,
                            final Directory.TypedWatcher watcher) {

        String bgpPath = paths.getBgpPath(bgpId);
        zk.asyncGet(bgpPath,
            DirectoryCallbackFactory.transform(
                bgpDirectoryCallback,
                new Functor<byte[], BGP>() {
                    @Override
                    public BGP apply(byte[] arg0) {
                        try {
                            return new BGP(
                                bgpId,
                                serializer.deserialize(arg0, BGP.Data.class));
                        } catch (SerializationException e) {
                            log.warn("Could not deserialize BGP data");
                            return null;
                        }
                    }
                }),
            watcher);
    }

    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getBgpPath(id));
    }

    public void getBgpListAsync(UUID portId,
                                final DirectoryCallback<Set<UUID>>
                                        bgpContentsCallback,
                                Directory.TypedWatcher watcher) {
        getUUIDSetAsync(paths.getPortBgpPath(portId),
                        bgpContentsCallback, watcher);
    }

    public List<UUID> list(UUID portId) throws StateAccessException {
        return getUuidList(paths.getPortBgpPath(portId));
    }

    public void update(UUID id, BGP config) throws StateAccessException,
            SerializationException {
        byte[] data = serializer.serialize(config);
        zk.update(paths.getBgpPath(id), data);
    }

    public void delete(UUID id) throws StateAccessException,
            SerializationException {
        zk.multi(prepareDelete(id));
        deleteStatus(id);
    }

    private void deleteStatus(UUID id) throws StateAccessException {
        String path = paths.getBgpStatusPath() + "/" + id.toString();
        try {
            if (zk.exists(path))
                zk.delete(path);
        } catch (NoStatePathException e) {} // ignored
    }

    public void setStatus(UUID id, String status) throws StateAccessException {
        String path = paths.getBgpStatusPath() + "/" + id.toString();
        log.debug("Setting BGP session status at {}", path);
        zk.ensureEphemeral(path, status.getBytes());
    }

    public String getStatus(UUID id) throws StateAccessException {
        String path = paths.getBgpStatusPath() + "/" + id.toString();
        try {
            return new String(zk.get(path));
        } catch (NoStatePathException e) {
            return "DOWN";
        }
    }

}
