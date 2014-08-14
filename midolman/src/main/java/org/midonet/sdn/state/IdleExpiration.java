/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.sdn.state;

import scala.concurrent.duration.Duration;

/**
 * An interface that marks types that have an idle time expiration.
 */
public interface IdleExpiration {
    Duration expiresAfter();
}
