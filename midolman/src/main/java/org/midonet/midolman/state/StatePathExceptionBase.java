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

/**
 * Base class for Zookeeper exceptions arising from a node either
 * existing when not expected (StatePathExistsException) or not
 * existing when expected (NoStatePathException). Has functionality
 * for identifying the resource associated with the path.
 */
public abstract class StatePathExceptionBase extends StateAccessException {

    private static final long serialVersionUID = 1L;

    // Path to node whose (non)existence caused the exception.
    protected final String path;

    public StatePathExceptionBase(String message, String path,
                                  KeeperException cause) {
        super(message, cause);
        this.path = path;
    }

    /**
     * Returns the path to the node whose (non)existence caused the exception.
     */
    public String getPath() {
        return path;
    }

}
