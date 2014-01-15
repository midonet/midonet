/**
 * Copyright 2013 Midokura KK
 */
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

/**
 * Class managing resources that can be assigned tags.
 *
 * @author tomohiko
 */
public class TaggableConfigZkManager extends AbstractZkManager {

    public TaggableConfigZkManager(ZkManager zk, PathBuilder paths, Serializer serializer) {
        super(zk, paths, serializer);
    }

    // This ideally should need only TaggableConfig, which should retain the ID
    // of the corresponding resource.
    // TODO(tomohiko) Refactor BridgeConfig, etc to keep the ID, and remove the
    // 'id' parameters below.
    public List<Op> prepareTagAdd(UUID id, TaggableConfig taggable, String tag) {
        List<Op> ops = new ArrayList<Op>();
        ops.add(createTagAddOp(id, taggable, tag));
        return ops;
    }

    private Op createTagAddOp(UUID id, TaggableConfig taggable, String tag) {
        String path = paths.getResourceTagPath(id, taggable, tag);
        return Op
                .create(path, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    public List<Op> prepareTagDelete(UUID id, TaggableConfig taggable, String tag) {
        List<Op> ops = new ArrayList<Op>();
        ops.add(zk.getDeleteOp(paths.getResourceTagPath(id, taggable, tag)));
        return ops;
    }

    /**
     * Gets a ZooKeeper node entry for a given tag attached to a taggable config.
     * A tag is currently simply a String, therefore if the tag exists for the
     * given taggable config, it returns the tag itself. Otherwise it throws an
     * exception.
     *
     * @param id The ID of the taggable resource.
     * @param taggable Config info for a taggable resource such as a bridge.
     * @param tag A resource tag.
     * @return A tag itself if found.
     * @throws StateAccessException If the tag doesn't exist.
     */
    public String get(UUID id, TaggableConfig taggable, String tag)
            throws StateAccessException {
        return get(id, taggable, tag, null);
    }

    /**
     * Gets a ZooKeeper node entry for a given tag attached to a taggable config.
     * A tag is currently simply a String, therefore if the tag exists for the
     * given taggable config, it returns the tag itself. Otherwise it throws an
     * exception.
     *
     * @param id The ID of the taggable resource.
     * @param taggable Config info for a taggable resource such as a bridge.
     * @param tag A resource tag.
     * @param watcher
     * @return A tag itself if found.
     * @throws StateAccessException If the tag doesn't exist.
     */
    public String get(UUID id, TaggableConfig taggable, String tag, Runnable watcher)
            throws StateAccessException {
        zk.get(paths.getResourceTagPath(id, taggable, tag), watcher);
        return tag;
    }

    /**
     * Returns a list of tags assigned to a resource with specified UUID and
     * TaggableConfig.
     *
     * @param id The UUID of the resource with tags.
     * @param taggable Config info for the taggable info.
     * @param watcher
     * @return A list of tag strings.
     * @throws StateAccessException
     */
    public List<String> listTags(UUID id, TaggableConfig taggable,
                              Runnable watcher) throws StateAccessException {
        String path = paths.getResourceTagsPath(id, taggable);
        return new ArrayList<String>(zk.getChildren(path, watcher));
    }

    /**
     * Create (assign) a new tag to a taggable resource (config).
     *
     * @param id UUID of taggable resource.
     * @param taggable Config info for the taggable resource.
     * @param tag A resource tag.
     * @throws StateAccessException
     */
    public void create(UUID id, TaggableConfig taggable, String tag)
            throws StateAccessException {
        create(id, taggable, tag, null);
    }

    /**
     * Create (assign) a new tag to a taggable resource (config).
     *
     * @param id UUID of taggable resource.
     * @param taggable Config info for the taggable resource.
     * @param tag A resource tag.
     * @param watcher
     * @throws StateAccessException
     */
    public void create(UUID id, TaggableConfig taggable, String tag, Runnable watcher)
            throws StateAccessException {
        zk.multi(prepareTagAdd(id, taggable, tag));
    }

    /**
     * Delete a tag assigned to the taggable resource (config).
     *
     * @param id UUID of taggable resource.
     * @param taggable Config info for the taggable resource.
     * @param tag A resource tag.
     * @throws StateAccessException
     */
    public void delete(UUID id, TaggableConfig taggable, String tag)
            throws StateAccessException {
        delete(id, taggable, tag, null);
    }

    /**
     * Delete a tag assigned to the taggable resource (config).
     *
     * @param id UUID of taggable resource.
     * @param taggable Config info for the taggable resource.
     * @param tag A resource tag.
     * @param watcher
     * @throws StateAccessException
     */
    public void delete(UUID id, TaggableConfig taggable, String tag, Runnable watcher)
            throws StateAccessException {
        zk.multi(prepareTagDelete(id, taggable, tag));
    }

}
