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

import java.util.List;
import java.util.UUID;

import org.midonet.cluster.data.BGP;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

public class BgpZkManager extends AbstractZkManager<UUID, BGP.Data> {

    AdRouteZkManager adRouteZkManager;

    /**
     * BgpZkManager constructor.
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

    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getBgpPath(id));
    }

    public List<UUID> list(UUID portId) throws StateAccessException {
        return getUuidList(paths.getPortBgpPath(portId));
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
