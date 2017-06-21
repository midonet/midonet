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

#include "jniTools.h"

const std::string jba2str(JNIEnv* env, jbyteArray array) {
    auto len = env->GetArrayLength(array);
    auto bytes = env->GetByteArrayElements(array, 0);
    auto str = std::string(reinterpret_cast<const char*>(array), len);
    env->ReleaseByteArrayElements(array, bytes, 0);
    return str;
}

jbyteArray str2jba(JNIEnv* env, const std::string& str) {
    jbyteArray array = env->NewByteArray(str.size());
    env->SetByteArrayRegion(array, 0, str.size(),
            reinterpret_cast<const jbyte*>(str.c_str()));
    return array;
}

