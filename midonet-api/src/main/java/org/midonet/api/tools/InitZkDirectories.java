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
package org.midonet.api.tools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;

public class InitZkDirectories {

    // private final static String tokenHeader = "HTTP_X_AUTH_TOKEN";

    /**
     * @param args
     * @throws IOException
     * @throws KeeperException
     * @throws InterruptedException
     */
    public static void main(String[] args) throws IOException,
            InterruptedException, KeeperException {
        ZooKeeper zk = new ZooKeeper(args[0], 300, null);
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create("/midonet", null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        ops.add(Op.create("/midonet/v1", null, Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));
        zk.multi(ops);

        /*
         * // TODO: Do better arg checking if (args.length != 2) { throw new
         * IllegalArgumentException( "Usage: InitZkDirectorires <API url>"); }
         * String url = args[0]; String token = args[1];
         *
         * Client client = Client.create(); WebResource webResource =
         * client.resource(url + "/admin/init"); ClientResponse response =
         * webResource.type(MediaType.APPLICATION_JSON) .header(tokenHeader,
         * token).post(ClientResponse.class, null); if (response.getStatus() !=
         * 200) { System.out.println("Error occurred:" +
         * response.getEntity(String.class)); } else {
         * System.out.println("Succeeded"); }
         */
        return;
    }

}
