/*
 * Copyright 2017 Midokura SARL
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
#ifndef _NATIVE_MAP_KEY_HASHER_H_
#define _NATIVE_MAP_KEY_HASHER_H_

#include <string>
#include <tbb/concurrent_hash_map.h>

/**
 * TBB default hasher for std::string expects strings to be
 * strings, not vector of chars.
 * STL default implementation works better for the vector of
 * chars use case (where the string may have a 0 at any location,
 * not only at the end). This class is intended to replace the
 * default TBB string hashing for concurrent hash maps.
 */
class NativeMapKeyHasher: public tbb::tbb_hash_compare<std::string> {
    public:

        static size_t hash(const std::string& str) {
            std::hash<std::string> hash_fn;
            return hash_fn(str);
        }

        static bool equal(const std::string& a, const std::string& b) {
            return a == b;
        }
};

#endif /* _NATIVE_MAP_KEY_HASHER_H_ */

