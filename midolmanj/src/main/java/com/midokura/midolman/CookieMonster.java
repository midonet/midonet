/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.collections.TypedHashMap;

public class CookieMonster {
    private static final Logger log =
            LoggerFactory.getLogger(CookieMonster.class);

    private TypedHashMap<UUID, Set<Long>> idToCookieSetMap =
            new TypedHashMap<UUID, Set<Long>>();
    private TypedHashMap<Set<UUID>, Long> idSetToCookieMap =
            new TypedHashMap<Set<UUID>, Long>();
    private long lastCookie = 0;

    public long getCookieForIdSet(Set<UUID> idSet) {
        // Return 0 (no cookie) if the ID set is empty.
        if (idSet.isEmpty())
            return 0;
        Long cookie = idSetToCookieMap.get(idSet);
        if (null == cookie) {
            // Generate a new cookie for this set
            lastCookie++;
            cookie = lastCookie;
            HashSet<UUID> ids = new HashSet<UUID>(idSet);
            idSetToCookieMap.put(ids, cookie);
            // Index the cookie by the IDs in the set.
            for (UUID id : ids) {
                Set<Long> cookieSet = idToCookieSetMap.get(id);
                if (null == cookieSet) {
                    cookieSet = new HashSet<Long>();
                    idToCookieSetMap.put(id, cookieSet);
                }
                cookieSet.add(cookie);
            }
            log.debug("Generated cookie for ID set {}", idSet);
        }
        // The cookie can never be null - one is always found or generated.
        return cookie.longValue();
    }

    public Set<Long> getCookieSetForID(UUID id) {
        Set<Long> cookies = idToCookieSetMap.get(id);
        return (null == cookies)
                ? new HashSet<Long>()
                : Collections.unmodifiableSet(cookies);
    }

    public long getNumCookies() {
        return lastCookie;
    }

    public boolean hasCookieForIdSet(Set<UUID> idSet) {
        return idSetToCookieMap.containsKey(idSet);
    }
}
