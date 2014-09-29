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
import org.midonet.cluster.data.storage.ServiceUnavailableException;
import org.midonet.cluster.data.storage.ZookeeperObjectMapper;
import org.midonet.cluster.data.storage.PersistenceOp;

import static org.midonet.api.validation.MessageProperty.ZOOM_CONCURRENT_MODIFICATION;
import static org.midonet.api.validation.MessageProperty.ZOOM_OBJECT_EXISTS_EXCEPTION;
import static org.midonet.api.validation.MessageProperty.ZOOM_OBJECT_NOT_FOUND_EXCEPTION;
import static org.midonet.api.validation.MessageProperty.ZOOM_OBJECT_REFERENCED_EXCEPTION;
import static org.midonet.api.validation.MessageProperty.ZOOM_REFERENCE_CONFLICT_EXCEPTION;
import static org.midonet.api.validation.MessageProperty.ZOOM_SERVER_ERROR;
import static org.midonet.api.validation.MessageProperty.getMessage;

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
        } catch (Exception ex) {
            wrapAndRethrow(ex, "create");
        }
    }

    @Override
    public void update(Object newThisObj) {
        try {
            super.update(newThisObj);
        } catch (NotFoundException | ReferenceConflictException ex) {
            wrapAndRethrow(ex, "update");
        }
    }

    @Override
    public void delete(Class<?> clazz, Object id) {
        try {
            super.delete(clazz, id);
        } catch (Exception ex) {
            // TODO: Catch and ignore NotFoundException for idempotency?
            wrapAndRethrow(ex, "delete");
        }
    }

    @Override
    public <T> T get(Class<T> clazz, Object id) {
        try {
            return super.get(clazz, id);
        } catch (Exception ex) {
            wrapAndRethrow(ex, "get");
            // Won't get here because wrapAndRethrow always throws, but the
            // compiler, or at least IDEA, doesn't know that, so we need this
            // to stop it from complaining about a path that doesn't return
            // a value.
            return null;
        }
    }

    @Override
    public void multi(List<PersistenceOp> ops) {
        try {
            super.multi(ops);
        } catch (Exception ex) {
            wrapAndRethrow(ex, "multi");
        }
    }

    @Override
    public void registerClass(Class<?> clazz) {
        super.registerClass(clazz);
    }

    @Override
    public boolean isRegistered(Class<?> clazz) {
        return super.isRegistered(clazz);
    }

    /**
     * Standard transformations for exceptions thrown by Zoom. If an operation
     * needs to handle a particular exception in a non-standard way, it can
     * catch that exception before it gets here.
     */
    private void wrapAndRethrow(Exception ex, String opName) {
        if (ex instanceof ConcurrentModificationException) {
            throw new ConflictHttpException(
                ex, getMessage(ZOOM_CONCURRENT_MODIFICATION));
        } else if (ex instanceof NotFoundException) {
            NotFoundException nfe = (NotFoundException)ex;
            throw new NotFoundHttpException(
                nfe, getMessage(ZOOM_OBJECT_NOT_FOUND_EXCEPTION,
                                nfe.clazz(), nfe.id()));
        } else if (ex instanceof ObjectExistsException) {
            ObjectExistsException oee = (ObjectExistsException)ex;
            throw new ConflictHttpException(
                oee, getMessage(ZOOM_OBJECT_EXISTS_EXCEPTION,
                                oee.clazz(), oee.id()));
        } else if (ex instanceof ObjectReferencedException) {
            ObjectReferencedException ore = (ObjectReferencedException)ex;
            throw new ConflictHttpException(
                ore, getMessage(ZOOM_OBJECT_REFERENCED_EXCEPTION,
                                ore.referencedClass(),
                                ore.referencedId(),
                                ore.referencingClass(),
                                ore.referencingId()));
        } else if (ex instanceof ReferenceConflictException) {
            ReferenceConflictException rce = (ReferenceConflictException)ex;
            throw new ConflictHttpException(
                rce, getMessage(ZOOM_REFERENCE_CONFLICT_EXCEPTION,
                                rce.referencingClass(),
                                rce.referencingId(),
                                rce.referencedClass(),
                                rce.referencedId(),
                                rce.referencingFieldName()));
        } else if (ex instanceof ServiceUnavailableException) {
            log.error("ZOOM service unavailable.", ex);
            throw new ServiceUnavailableHttpException();
        } else {
            log.error("Unexpected exception during ZOOM " + opName +
                      " operation.", ex);
            throw new InternalServerErrorHttpException(
                ex, getMessage(ZOOM_SERVER_ERROR));
        }
    }
}
