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
        } catch (NotFoundException nfe) {
            throw new NotFoundHttpException(
                    nfe, getMessage(ZOOM_OBJECT_NOT_FOUND_EXCEPTION,
                                    nfe.clazz(), nfe.id()));
        } catch (ObjectExistsException oee) {
            throw new ConflictHttpException(
                    oee, getMessage(ZOOM_OBJECT_EXISTS_EXCEPTION,
                                    oee.clazz(), oee.id()));
        } catch (ReferenceConflictException rce) {
            throw new ConflictHttpException(
                    rce, getMessage(ZOOM_REFERENCE_CONFLICT_EXCEPTION,
                                    rce.referencingClass(),
                                    rce.referencingId(),
                                    rce.referencedClass(),
                                    rce.referencedId(),
                                    rce.referencingFieldName()));
        } catch (ConcurrentModificationException cme) {
            throw new ConflictHttpException(
                    cme, getMessage(ZOOM_CONCURRENT_MODIFICATION));
        } catch (Exception ex) {
            log.error("Unexpected exception during ZOOM create.", ex);
            throw new InternalServerErrorHttpException(
                    ex, getMessage(ZOOM_SERVER_ERROR));
        }
    }

    @Override
    public void update(Object newThisObj) {
        try {
            super.update(newThisObj);
        } catch (NotFoundException nfe) {
            throw new NotFoundHttpException(
                    nfe, getMessage(ZOOM_OBJECT_NOT_FOUND_EXCEPTION,
                                    nfe.clazz(), nfe.id()));
        } catch (ReferenceConflictException rce) {
            throw new ConflictHttpException(
                    rce, getMessage(ZOOM_REFERENCE_CONFLICT_EXCEPTION,
                                    rce.referencingClass(),
                                    rce.referencingId(),
                                    rce.referencedClass(),
                                    rce.referencedId(),
                                    rce.referencingFieldName()));
        } catch (ConcurrentModificationException cme) {
            throw new ConflictHttpException(
                    cme, getMessage(ZOOM_CONCURRENT_MODIFICATION));
        } catch (Exception ex) {
            log.error("Unexpected exception during ZOOM update.", ex);
            throw new InternalServerErrorHttpException(
                    ex, getMessage(ZOOM_SERVER_ERROR));
        }
    }

    @Override
    public void delete(Class<?> clazz, Object id) {
        try {
            super.delete(clazz, id);
        } catch (NotFoundException nfe) {
            // TODO: Or ignore?
            throw new NotFoundHttpException(
                    nfe, getMessage(ZOOM_OBJECT_NOT_FOUND_EXCEPTION,
                                    nfe.clazz(), nfe.id()));
        } catch (ObjectReferencedException ore) {
            throw new ConflictHttpException(
                    ore, getMessage(ZOOM_OBJECT_REFERENCED_EXCEPTION,
                                    ore.referencedClass(),
                                    ore.referencedId(),
                                    ore.referencingClass(),
                                    ore.referencingId()));
        } catch (ConcurrentModificationException cme) {
            throw new ConflictHttpException(
                    cme, getMessage(ZOOM_CONCURRENT_MODIFICATION));
        } catch (Exception ex) {
            log.error("Unexpected exception during ZOOM delete.", ex);
            throw new InternalServerErrorHttpException(
                    ex, getMessage(ZOOM_SERVER_ERROR));
        }
    }

    @Override
    public <T> T get(Class<T> clazz, Object id) {
        try {
            return super.get(clazz, id);
        } catch (NotFoundException nfe) {
            throw new NotFoundHttpException(
                    nfe, getMessage(ZOOM_OBJECT_NOT_FOUND_EXCEPTION,
                                    nfe.clazz(), nfe.id()));
        } catch (Exception ex) {
            log.error("Unexpected exception during ZOOM get.", ex);
            throw new InternalServerErrorHttpException(
                    ex, getMessage(ZOOM_SERVER_ERROR));
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
