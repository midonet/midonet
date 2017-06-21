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

#include <nativeMeterRegistry.h>
#include "jniTools.h"
#include "org_midonet_midolman_monitoring_NativeMeterRegistryJNI.h"

//
// JNI methods
//

static jclass jc_string;
void
Java_org_midonet_midolman_monitoring_NativeMeterRegistryJNI_initialize(
        JNIEnv* env) {
    jc_string = env->FindClass("java/lang/String");
}

jlong
Java_org_midonet_midolman_monitoring_NativeMeterRegistryJNI_create(
        JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new NativeMeterRegistry());
}

jobjectArray
Java_org_midonet_midolman_monitoring_NativeMeterRegistryJNI_getMeterKeys(
        JNIEnv* env, jobject, jlong ptr) {
    auto reg = reinterpret_cast<NativeMeterRegistry*>(ptr);
    auto keys = reg->get_meter_keys();
    jobjectArray array = env->NewObjectArray(keys.size(), jc_string, 0);

    int i = 0;
    for (auto item: keys) {
        jstring str = env->NewStringUTF(item.c_str());
        env->SetObjectArrayElement(array, i, str);
        ++i;
    }

    return array;
}

jlongArray
Java_org_midonet_midolman_monitoring_NativeMeterRegistryJNI_getMeter(
        JNIEnv* env, jobject, jlong ptr, jstring key) {
    auto reg = reinterpret_cast<NativeMeterRegistry*>(ptr);
    auto str = env->GetStringUTFChars(key, 0);
    std::string meter(str);
    env->ReleaseStringUTFChars(key, str);
    auto item = reg->get_meter(meter);
    if (item.is_defined()) {
        jlong buffer[] = {item.value().get_packets(), item.value().get_bytes()};
        jlongArray fs = env->NewLongArray(2);
        env->SetLongArrayRegion(fs, 0, 2, buffer);
        return fs;
    } else {
        return 0;
    }
}

/**
 * Convert an array of strings from java (jobjectArray) into C++ string list
 */
static std::vector<std::string>
joa2strvect(JNIEnv* env, jobjectArray strings) {
    int len = env->GetArrayLength(strings);
    std::vector<std::string> str_list;
    str_list.reserve(len);
    for (int i = 0; i < len; ++i) {
        auto item = (jstring)(env->GetObjectArrayElement(strings, i));
        auto str = env->GetStringUTFChars(item, 0);
        str_list.push_back(std::string(str));
        env->ReleaseStringUTFChars(item, str);
    }
    return str_list;
}

void
Java_org_midonet_midolman_monitoring_NativeMeterRegistryJNI_trackFlow(
JNIEnv* env, jobject, jlong ptr, jbyteArray fm, jobjectArray tag_list) {
    auto reg = reinterpret_cast<NativeMeterRegistry*>(ptr);
    FlowMatch flow(jba2str(env, fm));
    std::vector<MeterTag> tags = joa2strvect(env, tag_list);
    reg->track_flow(flow, tags);
}

void
Java_org_midonet_midolman_monitoring_NativeMeterRegistryJNI_recordPacket(
JNIEnv* env, jobject, jlong ptr, jint len, jobjectArray tag_list) {
    auto reg = reinterpret_cast<NativeMeterRegistry*>(ptr);
    std::vector<MeterTag> tags = joa2strvect(env, tag_list);
    reg->record_packet(len, tags);
}

void
Java_org_midonet_midolman_monitoring_NativeMeterRegistryJNI_updateFlow(
JNIEnv* env, jobject, jlong ptr, jbyteArray fm, jlong packets, jlong bytes) {
    auto reg = reinterpret_cast<NativeMeterRegistry*>(ptr);
    FlowMatch flow(jba2str(env, fm));
    NativeFlowStats stats(packets, bytes);
    reg->update_flow(flow, stats);
}

void
Java_org_midonet_midolman_monitoring_NativeMeterRegistryJNI_forgetFlow(
JNIEnv* env, jobject, jlong ptr, jbyteArray fm) {
    auto reg = reinterpret_cast<NativeMeterRegistry*>(ptr);
    FlowMatch flow(jba2str(env, fm));
    reg->forget_flow(flow);
}


//
// NativeMeterRegistry implementation
//

NativeMeterRegistry::NativeMeterRegistry(): meters() {}

std::vector<MeterTag>
NativeMeterRegistry::get_meter_keys() const {
    // Take advantage of move semantics...
    std::vector<MeterTag> keys;
    keys.reserve(meters.size());
    // NOTE: this cannot be executed concurrently with find/insert/erase
    //       this should be fine since this method is only used for testing.
    for (auto it: meters) {
        keys.push_back(it.first);
    }
    return keys;
}

Option<NativeFlowStats>
NativeMeterRegistry::get_meter(const MeterTag& key) const {
    MeterMap::const_accessor item;
    if (meters.find(item, key)) {
        NativeFlowStats fs = item->second;
        return Option<NativeFlowStats>(fs);
    } else {
        return Option<NativeFlowStats>::none;
    }
}

void
NativeMeterRegistry::track_flow(const FlowMatch& fm,
        const std::vector<MeterTag>& meter_tags) {
    if (flows.count(fm))
        return;

    NativeFlowStats EMPTY_STATS;
    FlowDataPtr data = std::make_shared<FlowData>();

    for (auto tag: meter_tags) {
        data->tags.push_back(tag);
        MeterMap::value_type new_stats(tag, EMPTY_STATS);
        meters.insert(new_stats);
    }
    if (data->tags.size() > 0) {
        FlowMap::value_type new_flow = {fm, data};
        flows.insert(new_flow);
    }
}

void
NativeMeterRegistry::record_packet(int32_t packet_len,
        const std::vector<MeterTag>& tags) {
    NativeFlowStats delta(1, packet_len);

    for (auto tag: tags) {
        MeterMap::accessor data;
        MeterMap::value_type item(tag, delta);
        if (!meters.insert(data, item)) {
            // value already present
            data->second.add(delta);
        }
    }
}

void
NativeMeterRegistry::update_flow(const FlowMatch& fm,
        const NativeFlowStats& stats) {
    FlowMap::accessor data;
    if (flows.find(data, fm)) {
        NativeFlowStats delta(stats);
        delta.subtract(data->second->stats);
        if (delta.underflow()) {
            delta = data->second->stats = stats;
        } else {
            data->second->stats.add(delta);
        }
        for (auto tag: data->second->tags) {
            MeterMap::accessor meter;
            if (meters.find(meter, tag)) {
                meter->second.add(delta);
            }
        }
    }
}

void
NativeMeterRegistry::forget_flow(const FlowMatch& fm) {
    flows.erase(fm);
}

