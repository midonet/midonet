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

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import org.apache.zookeeper.Watcher;
import org.junit.Assert;

import org.midonet.cluster.backend.Directory;

/**
 * Test helper class to verify the directory content.
 *
 * When matching on the directory data, use the JSON syntax to query:
 *
 *     https://code.google.com/p/json-path/
 */
public class DirectoryVerifier {

    private static final String ROOT_DATA_PATH = "$.data.";
    private final Directory directory;

    public DirectoryVerifier(Directory directory) {
        Preconditions.checkNotNull(directory);
        this.directory = directory;
    }

    private String buildQueryPath(String path) {
        return ROOT_DATA_PATH + path;
    }

    public void assertPathExists(String path) {
        Preconditions.checkNotNull(path);

        try {
            Assert.assertTrue(this.directory.exists(path, (Watcher)null));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private String getJsonStr(String path) {
        try {
            byte[] byteData = this.directory.get(path, null);
            return new String(byteData);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private Set<String> getChildPaths(String path) {

        Set<String> paths = new HashSet<>();
        try {
            Set<String> dirs = this.directory.getChildren(path, null);
            for (String dir : dirs) {
                paths.add(path + "/" + dir);
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return paths;
    }

    private boolean fieldMatches(String strData, String key, Object value) {

        String q = buildQueryPath(key);
        try {
            Object item = JsonPath.read(strData, q);
            return Objects.equals(item.toString(), value.toString());
        } catch (PathNotFoundException ex) {
            return false;
        }
    }

    private boolean fieldsMatch(String strData,
                                final Map<String, Object> matches) {

        boolean matched = true;
        for (Map.Entry<String, Object> match : matches.entrySet()) {

            if (!fieldMatches(strData, match.getKey(), match.getValue())) {
                matched = false;
                break;
            }
        }
        return matched;
    }

    private int getFieldMatchCount(String parent,
                                   Function<String, Boolean> matcher) {
        int cnt = 0;
        Set<String> paths = getChildPaths(parent);
        for (String path : paths) {
            Boolean matched = matcher.apply(path);
            if (matched != null && matched) {
                cnt++;
            }
        }
        return cnt;
    }

    public void assertFieldMatches(String path, String key, Object value) {
        Preconditions.checkNotNull(key);
        Preconditions.checkNotNull(value);
        assertPathExists(path);

        Assert.assertTrue(fieldMatches(getJsonStr(path), key, value));
    }

    public void assertFieldsMatch(String path,
                                  final Map<String, Object> matches) {
        Preconditions.checkNotNull(matches);
        assertPathExists(path);

        Assert.assertTrue(fieldsMatch(getJsonStr(path), matches));
    }

    public void assertChildrenFieldMatches(
        String parent, final String key, final Object value, int expectedCnt) {

        Preconditions.checkNotNull(key);
        Preconditions.checkNotNull(value);
        Preconditions.checkArgument(expectedCnt >= 0);
        assertPathExists(parent);

        int actualCnt = getFieldMatchCount(
            parent,
            new Function<String, Boolean>() {

                @Nullable
                @Override
                public Boolean apply(@Nullable String path) {
                    return fieldMatches(getJsonStr(path), key, value);
                }
            });

        Assert.assertEquals(expectedCnt, actualCnt);
    }

    public void assertChildrenFieldsMatch(
        String parent, final Map<String, Object> matches, int expectedCnt) {

        Preconditions.checkNotNull(matches);
        Preconditions.checkArgument(expectedCnt >= 0);
        assertPathExists(parent);

        int actualCnt = getFieldMatchCount(
            parent,
            new Function<String, Boolean>() {

                @Nullable
                @Override
                public Boolean apply(@Nullable String path) {
                    return fieldsMatch(getJsonStr(path), matches);
                }
            });

        Assert.assertEquals(expectedCnt, actualCnt);
    }
}
