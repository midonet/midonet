/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.midolman.state;

import org.midonet.midolman.serialization.Serializer;
import org.midonet.util.functors.Functor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;


/**
 *  Abstract class for the Zookeeper manager classes
 */
public abstract class AbstractZkManager {

    protected final static Logger log =
            LoggerFactory.getLogger(AbstractZkManager.class);

    protected final ZkManager zk;
    protected final PathBuilder paths;
    protected final Serializer serializer;

    protected static final Functor<String, UUID> strToUUIDMapper =
            new Functor<String, UUID>() {
                @Override
                public UUID apply(String arg0) {
                    try {
                        return UUID.fromString(arg0);
                    } catch (IllegalArgumentException ex) {
                        return null;
                    }
                }
            };

    /**
     * Constructor.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public AbstractZkManager(ZkManager zk, PathBuilder paths,
                             Serializer serializer) {
        this.zk = zk;
        this.paths = paths;
        this.serializer = serializer;
    }
}
