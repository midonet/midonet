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
  std::cout << "Creating new NativeTimedExpirationMap" << std::endl;
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

jint
Java_org_midonet_util_concurrent_NativeTimedExpirationMap_getRefCount
(JNIEnv *env, jobject, jlong ptr, jbyteArray keyBytes) {
  auto map = reinterpret_cast<NativeTimedExpirationMap*>(ptr);
  return map->get_ref_count(jba2str(env, keyBytes));
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
(JNIEnv *env, jobject, jlong ptr, jbyteArray keyBytes, jlong expiry) {
  auto map = reinterpret_cast<NativeTimedExpirationMap*>(ptr);
  auto ret = map->unref(jba2str(env, keyBytes), expiry);
  if (ret) {
    return str2jba(env, ret.value());
  } else {
    return NULL;
  }
}

void Java_org_midonet_util_concurrent_NativeTimedExpirationMap_destroy
(JNIEnv *, jobject, jlong ptr) {
  auto map = reinterpret_cast<NativeTimedExpirationMap*>(ptr);
  delete map;
}

const option<std::string>
NativeTimedExpirationMap::put_and_ref(const std::string key,
                                      const std::string value) {
  std::cout << "NativeTimedExpirationMap::put_and_ref(" << key
            << ", " << value << ")" << std::endl;
  auto it = ref_count_map.find(key);
  if (it != ref_count_map.end()) {
    auto ret = ref(key);
    ref_count_map[key].set_value(value);
    return ret;
  } else {
    ref_count_map[key] = Metadata(value, 1, LONG_MAX);
    return option<std::string>::null_opt;
  }
}

int
NativeTimedExpirationMap::put_if_absent_and_ref(const std::string key,
                                                const std::string value) {
  std::cout << "NativeTimedExpirationMap::put_if_absent_and_ref(" << key
            << ", " << value << ")" << std::endl;
  auto it = ref_count_map.find(key);
  if (it != ref_count_map.end()) {
    return ref_and_get_count(key);
  } else {
    put_and_ref(key, value);
    return 1;
  }
}

const option<std::string>
NativeTimedExpirationMap::get(const std::string key) const {
  auto it = ref_count_map.find(key);
  if (it != ref_count_map.end() && it->second.ref_count() != -1) {
    return option<std::string>(it->second.value());
  } else {
    return option<std::string>::null_opt;
  }
}

int
NativeTimedExpirationMap::get_ref_count(const std::string key) const {
  std::cout << "NativeTimedExpirationMap::get_ref_count("
            << key << ")" << std::endl;
  return 0;
}

const option<std::string>
NativeTimedExpirationMap::ref(const std::string key) {
  std::cout << "NativeTimedExpirationMap::ref("
            << key << ")" << std::endl;
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
NativeTimedExpirationMap::ref_and_get_count(const std::string key) {
  std::cout << "NativeTimedExpirationMap::ref_and_get_count("
            << key << ")" << std::endl;
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
NativeTimedExpirationMap::ref_count(const std::string key) const {
  std::cout << "NativeTimedExpirationMap::ref_count("
            << key << ")" << std::endl;
  auto it = ref_count_map.find(key);
  if (it != ref_count_map.end()) {
    it->second.ref_count();
  } else {
    return 0;
  }
}

const option<std::string>
NativeTimedExpirationMap::unref(const std::string key, long expiry) {
  std::cout << "NativeTimedExpirationMap::unref("
            << key << ", " << expiry << ")" << std::endl;
  return option<std::string>::null_opt;
}
