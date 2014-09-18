/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.rest_api;

import java.util.ConcurrentModificationException;
import java.util.List;

import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.data.storage.NotFoundException;
import org.midonet.cluster.data.storage.ObjectExistsException;
import org.midonet.cluster.data.storage.ObjectReferencedException;
import org.midonet.cluster.data.storage.ReferenceConflictException;
import org.midonet.cluster.data.storage.ZookeeperObjectMapper;
import org.midonet.cluster.data.storage.PersistenceOp;

public class HttpAwareZoom extends ZookeeperObjectMapper {
    private static final Logger log =
        LoggerFactory.getLogger(HttpAwareZoom.class);

    public HttpAwareZoom(String basePath, CuratorFramework curator) {
        super(basePath, curator);
    }

    @Override
    public void create(Object obj) {
        try {
            super.create(obj);
        } catch (NotFoundException | ObjectExistsException |
                 ReferenceConflictException |
                 ConcurrentModificationException ex) {
            throw new ConflictHttpException(ex.getMessage());
        } catch (Exception ex) {
            log.error("Unexpected exception during ZOOM create.", ex);
            throw new InternalServerErrorHttpException();
        }
    }

    @Override
    public void update(Object newThisObj) {
        try {
            super.update(newThisObj);
        } catch (NotFoundException ex) {
            throw new NotFoundHttpException(ex.getMessage());
        } catch (ReferenceConflictException |
                 ConcurrentModificationException ex) {
            throw new ConflictHttpException(ex.getMessage());
        } catch (Exception ex) {
            log.error("Unexpected exception during ZOOM update.", ex);
            throw new InternalServerErrorHttpException();
        }
    }

    @Override
    public void delete(Class<?> clazz, Object id) {
        try {
            super.delete(clazz, id);
        } catch (NotFoundException ex) {
            // TODO: Or ignore?
            throw new NotFoundHttpException(ex.getMessage());
        } catch (ObjectReferencedException |
                 ConcurrentModificationException ex) {
            throw new ConflictHttpException(ex.getMessage());
        } catch (Exception ex) {
            log.error("Unexpected exception during ZOOM delete.", ex);
            throw new InternalServerErrorHttpException();
        }
    }

    @Override
    public <T> T get(Class<T> clazz, Object id) {
        try {
            return super.get(clazz, id);
        } catch (NotFoundException ex) {
            throw new NotFoundHttpException(ex.getMessage());
        } catch (Exception ex) {
            log.error("Unexpected exception during ZOOM get.", ex);
            throw new InternalServerErrorHttpException();
        }
    }

    @Override
    public void multi(List<PersistenceOp> ops) {
        super.multi(ops);
    }

    @Override
    public void registerClass(Class<?> clazz) {
        super.registerClass(clazz);
    }

    @Override
    public boolean isRegistered(Class<?> clazz) {
        return super.isRegistered(clazz);
    }
}
