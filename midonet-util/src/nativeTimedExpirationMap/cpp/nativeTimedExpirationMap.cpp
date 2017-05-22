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
#include <iostream>
#include <nativeTimedExpirationMap.h>
#include "org_midonet_util_concurrent_NativeTimedExpirationMap.h"


jlong Java_org_midonet_util_concurrent_NativeTimedExpirationMap_create(JNIEnv *, jobject) {
  return reinterpret_cast<jlong>(new NativeTimedExpirationMap());
}

const std::string jba2str(JNIEnv *env, jbyteArray ba) {
  auto len = env->GetArrayLength(ba);
  auto bytes = reinterpret_cast<const char*>(env->GetByteArrayElements(ba, 0));
  return std::string(bytes, len);
}

jbyteArray str2jba(JNIEnv *env, std::string str) {
  jbyteArray retBytes = env->NewByteArray(str.size());
  env->SetByteArrayRegion(retBytes, 0, str.size(),
                          reinterpret_cast<const jbyte*>(str.c_str()));
  return retBytes;
}

jbyteArray
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_putAndRef
(JNIEnv *env, jobject, jlong ptr, jbyteArray keyBytes, jbyteArray valBytes) {
  auto map = reinterpret_cast<NativeTimedExpirationMap*>(ptr);
  auto ret = map->put_and_ref(jba2str(env, keyBytes), jba2str(env, valBytes));
  if (ret) {
    return str2jba(env, ret.value());
  } else {
    return NULL;
  }
}

jint
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_putIfAbsentAndRef
(JNIEnv *env, jobject, jlong ptr, jbyteArray keyBytes, jbyteArray valBytes) {
  auto map = reinterpret_cast<NativeTimedExpirationMap*>(ptr);
  return map->put_if_absent_and_ref(jba2str(env, keyBytes),
                                    jba2str(env, valBytes));
}

jbyteArray
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_get
(JNIEnv *env, jobject, jlong ptr, jbyteArray keyBytes) {
  auto map = reinterpret_cast<NativeTimedExpirationMap*>(ptr);
  auto ret = map->get(jba2str(env, keyBytes));
  if (ret) {
    return str2jba(env, ret.value());
  } else {
    return NULL;
  }
}

jbyteArray
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_ref
(JNIEnv *env, jobject, jlong ptr, jbyteArray keyBytes) {
  auto map = reinterpret_cast<NativeTimedExpirationMap*>(ptr);
  auto ret = map->ref(jba2str(env, keyBytes));
  if (ret) {
    return str2jba(env, ret.value());
  } else {
    return NULL;
  }
}

jint
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_refAndGetCount
(JNIEnv *env, jobject, jlong ptr, jbyteArray keyBytes) {
  auto map = reinterpret_cast<NativeTimedExpirationMap*>(ptr);
  return map->ref_and_get_count(jba2str(env, keyBytes));
}

jint
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_refCount
(JNIEnv *env, jobject, jlong ptr, jbyteArray keyBytes) {
  auto map = reinterpret_cast<NativeTimedExpirationMap*>(ptr);
  return map->ref_count(jba2str(env, keyBytes));
}

jbyteArray
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_unref
(JNIEnv *env, jobject, jlong ptr, jbyteArray keyBytes,
 jlong expire_in, jlong current_time_millis) {
  auto map = reinterpret_cast<NativeTimedExpirationMap*>(ptr);
  auto ret = map->unref(jba2str(env, keyBytes), expire_in, current_time_millis);
  if (ret) {
    return str2jba(env, ret.value());
  } else {
    return NULL;
  }
}

void
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_destroy
(JNIEnv *, jobject, jlong ptr) {
  auto map = reinterpret_cast<NativeTimedExpirationMap*>(ptr);
  delete map;
}

jlong
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_iterator
(JNIEnv *, jobject, jlong ptr) {
  auto map = reinterpret_cast<NativeTimedExpirationMap*>(ptr);
  return reinterpret_cast<jlong>(map->iterator());
}

jlong
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_obliterate
(JNIEnv *, jobject, jlong ptr, jlong currentTimeMillis) {
  auto map = reinterpret_cast<NativeTimedExpirationMap*>(ptr);
  return reinterpret_cast<jlong>(map->obliterate(currentTimeMillis));
}

jboolean
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_iteratorAtEnd
(JNIEnv *, jobject, jlong iteratorPtr) {
  auto iter = reinterpret_cast<NativeTimedExpirationMap::Iterator*>(iteratorPtr);
  return iter->at_end();
}

void
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_iteratorNext
(JNIEnv *, jobject, jlong iteratorPtr) {
  auto iter = reinterpret_cast<NativeTimedExpirationMap::Iterator*>(iteratorPtr);
  iter->next();
}

jbyteArray
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_iteratorCurKey
(JNIEnv *env, jobject, jlong iteratorPtr) {
  auto iter = reinterpret_cast<NativeTimedExpirationMap::Iterator*>(iteratorPtr);
  return str2jba(env, iter->cur_key());
}

jbyteArray
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_iteratorCurValue
(JNIEnv *env, jobject, jlong iteratorPtr) {
  auto iter = reinterpret_cast<NativeTimedExpirationMap::Iterator*>(iteratorPtr);
  return str2jba(env, iter->cur_value());
}

void
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_iteratorClose
(JNIEnv *, jobject, jlong iteratorPtr) {
  auto iter = reinterpret_cast<NativeTimedExpirationMap::Iterator*>(iteratorPtr);
  delete iter;
}

const option<std::string>
NativeTimedExpirationMap::put_and_ref(const std::string key,
                                      const std::string value) {
  std::lock_guard<std::recursive_mutex> lock(mutex);
  return put_and_ref_no_lock(key, value);
}

int
NativeTimedExpirationMap::put_if_absent_and_ref(const std::string key,
                                                const std::string value) {
  std::lock_guard<std::recursive_mutex> lock(mutex);
  return put_if_absent_and_ref_no_lock(key, value);
}

const option<std::string>
NativeTimedExpirationMap::get(const std::string key) const {
  std::lock_guard<std::recursive_mutex> lock(mutex);
  return get_no_lock(key);
}

const option<std::string>
NativeTimedExpirationMap::ref(const std::string key) {
  std::lock_guard<std::recursive_mutex> lock(mutex);
  return ref_no_lock(key);
}

int
NativeTimedExpirationMap::ref_and_get_count(const std::string key) {
  std::lock_guard<std::recursive_mutex> lock(mutex);
  return ref_and_get_count_no_lock(key);
}

int
NativeTimedExpirationMap::ref_count(const std::string key) const {
  std::lock_guard<std::recursive_mutex> lock(mutex);
  return ref_count_no_lock(key);
}

const option<std::string>
NativeTimedExpirationMap::unref(const std::string key,
                                long expire_in,
                                long current_time_millis) {
  std::lock_guard<std::recursive_mutex> lock(mutex);
  return unref_no_lock(key, expire_in, current_time_millis);
}


const option<std::string>
NativeTimedExpirationMap::put_and_ref_no_lock(const std::string key,
                                              const std::string value) {
  auto it = ref_count_map.find(key);
  if (it != ref_count_map.end()) {
    auto ret = ref_no_lock(key);
    ref_count_map[key].set_value(value);
    return ret;
  } else {
    ref_count_map[key] = Metadata(value, 1, LONG_MAX);
    return option<std::string>::null_opt;
  }
}

int
NativeTimedExpirationMap::put_if_absent_and_ref_no_lock(const std::string key,
                                                        const std::string value) {
  auto it = ref_count_map.find(key);
  if (it != ref_count_map.end()) {
    return ref_and_get_count_no_lock(key);
  } else {
    put_and_ref_no_lock(key, value);
    return 1;
  }
}

const option<std::string>
NativeTimedExpirationMap::get_no_lock(const std::string key) const {
  auto it = ref_count_map.find(key);
  if (it != ref_count_map.end() && it->second.ref_count() != -1) {
    return option<std::string>(it->second.value());
  } else {
    return option<std::string>::null_opt;
  }
}

const option<std::string>
NativeTimedExpirationMap::ref_no_lock(const std::string key) {
  auto it = ref_count_map.find(key);
  if (it != ref_count_map.end()) {
    auto count = it->second.inc_if_greater_than(-1);
    if (count == -1) {
      return option<std::string>::null_opt;
    } else {
      return option<std::string>(it->second.value());
    }
  } else {
    return option<std::string>::null_opt;
  }
}

int
NativeTimedExpirationMap::ref_and_get_count_no_lock(const std::string key) {
  auto it = ref_count_map.find(key);
  if (it != ref_count_map.end()) {
    auto new_count = it->second.inc_if_greater_than(-1);
    if (new_count == -1) {
      return 0;
    } else {
      return new_count;
    }
  } else {
    return 0;
  }
}

int
NativeTimedExpirationMap::ref_count_no_lock(const std::string key) const {
  auto it = ref_count_map.find(key);
  if (it != ref_count_map.end()) {
    it->second.ref_count();
  } else {
    return 0;
  }
}

const option<std::string>
NativeTimedExpirationMap::unref_no_lock(const std::string key,
                                        long expire_in,
                                        long current_time_millis) {
  auto it = ref_count_map.find(key);
  if (it != ref_count_map.end()) {
    if (it->second.ref_count() > 0 &&
        it->second.dec_and_get() == 0) {
      long expiration = current_time_millis + expire_in;
      it->second.set_expiration(expiration);
      expiring[expire_in].push(std::make_pair(key, expiration));
    }
    return option<std::string>(it->second.value());
  } else {
    return option<std::string>::null_opt;
  }
}

NativeTimedExpirationMap::Iterator* NativeTimedExpirationMap::iterator() const {
  return new NativeTimedExpirationMap::AllEntriesIterator(mutex,
                                                          ref_count_map);
}

NativeTimedExpirationMap::Iterator* NativeTimedExpirationMap::obliterate(long current_time_millis) {
  return new NativeTimedExpirationMap::ObliterationIterator(mutex,
                                                            expiring,
                                                            ref_count_map,
                                                            current_time_millis);
}

NativeTimedExpirationMap::AllEntriesIterator::AllEntriesIterator(std::recursive_mutex& mutex,
                                                                 const std::unordered_map<std::string, Metadata>& map)
  : lock(mutex), iterator(map.cbegin()), end(map.cend()) {}

bool NativeTimedExpirationMap::AllEntriesIterator::at_end() const {
  return iterator == end;
}

void NativeTimedExpirationMap::AllEntriesIterator::next() {
  iterator++;
}

std::string NativeTimedExpirationMap::AllEntriesIterator::cur_key() const {
  return iterator->first;
}

std::string NativeTimedExpirationMap::AllEntriesIterator::cur_value() const {
  return iterator->second.value();
}

NativeTimedExpirationMap::ObliterationIterator::ObliterationIterator(std::recursive_mutex& mutex,
                                                                     std::unordered_map<long, std::queue<std::pair<std::string,long>>>& expiring,
                                                                     std::unordered_map<std::string, Metadata>& ref_count_map,
                                                                     long current_time_millis)
  : lock(mutex), expiring(expiring), ref_count_map(ref_count_map),
    current_time_millis(current_time_millis),
    queue_iterator(expiring.begin()), current(option<KeyVal>::null_opt) {
  next();
}

void NativeTimedExpirationMap::ObliterationIterator::progress_iterator() {
  while (queue_iterator != expiring.end()
         && (queue_iterator->second.empty()
             || queue_iterator->second.front().second > current_time_millis)) {
    queue_iterator++;
  }
}

bool NativeTimedExpirationMap::ObliterationIterator::at_end() const {
  return !current
    && (queue_iterator == expiring.end()
        || queue_iterator->second.empty()
        || queue_iterator->second.front().second > current_time_millis);
}

void NativeTimedExpirationMap::ObliterationIterator::next() {
  current = option<KeyVal>::null_opt;
  progress_iterator();

  while (!at_end() && !current) {
    auto current_key = queue_iterator->second.front().first;

    auto it = ref_count_map.find(current_key);
    if (it != ref_count_map.end() && it->second.dec_if_zero()) {
      auto current_value = it->second.value();
      current = option<KeyVal>(std::make_pair(current_key, current_value));
      ref_count_map.erase(it);
      queue_iterator->second.pop();
    } else {
      queue_iterator->second.pop();
      progress_iterator();
    }
  }
}

std::string NativeTimedExpirationMap::ObliterationIterator::cur_key() const {
  return current.value().first;
}

std::string NativeTimedExpirationMap::ObliterationIterator::cur_value() const {
  return current.value().second;
}

