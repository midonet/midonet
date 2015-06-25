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

import java.util.Map;

public interface CheckpointedDirectory extends Directory {

    /**
     * Creates a new checkpoint at the root of the Directory.
     *
     * @return The id of the checkpoint.
     */
    int createCheckPoint();

    /**
     * Creates a new checkpoint at the given path of the Directory.
     *
     * @param path path where we want the checkpoint to apply. The checkpoint
     *             will only store node/values that are under this path.
     * @return The id of the checkpoint.
     */
    int createCheckPoint(String path);

    /**
     * returns a map of all of the added paths (paths present in checkpoint 2
     * that are not present in checkpoint 1.
     *
     * @param cpIndex1 index of the first checkpoint.
     * @param cpIndex2 index of the second checkpoint.
     * @return map of added paths to data.
     */
    Map<String, String> getAddedPaths(int cpIndex1, int cpIndex2);

    /**
     * returns a map of all of the removed paths (paths present in checkpoint 1
     * that are not present in checkpoint 2.
     *
     * @param cpIndex1 index of the first checkpoint.
     * @param cpIndex2 index of the second checkpoint.
     * @return map of removed paths to data.
     */
    Map<String, String> getRemovedPaths(int cpIndex1, int cpIndex2);

    /**
     * returns a map of all of the modified paths (paths present in checkpoint 1
     * and checkpoint 2 where the data is different.
     *
     * @param cpIndex1 index of the first checkpoint.
     * @param cpIndex2 index of the second checkpoint.
     * @return map of removed paths to data.
     */
    Map<String, String> getModifiedPaths(int cpIndex1, int cpIndex2);

}
