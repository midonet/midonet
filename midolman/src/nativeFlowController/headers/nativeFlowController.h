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
#ifndef _NATIVE_FLOW_CONTROLLER_H_
#define _NATIVE_FLOW_CONTROLLER_H_

#include <string>
#include <vector>
#include <unordered_set>
#include <unordered_map>

using FlowId = long long;
using FlowTag = long long;
const FlowId NULL_ID = -839193346820535158;

// See FlowController.scala
const int INDEX_SHIFT = 28;
const int INDEX_MASK = (1 << INDEX_SHIFT) - 1;
const int MAX_TABLE_SIZE = INDEX_MASK;

class CallbackSpec {
public:
  CallbackSpec();
  CallbackSpec(long long cb_id, std::string args);

  long long cb_id() const;
  std::string args() const;

private:
  long long m_cb_id;
  std::string m_args;
};

class Flow {
public:
  Flow();
  Flow(FlowId id, std::string& flow_match);
  FlowId id() const;
  std::string flow_match() const;

  long long sequence() const;
  void set_sequence(long long sequence);

  FlowId linked_id() const;
  void set_linked_id(FlowId linked_id);

  std::vector<CallbackSpec> callbacks() const;
  void add_callback(CallbackSpec spec);

private:
  FlowId m_id;
  long long m_sequence;
  FlowId m_linked_id;
  std::string m_flow_match;
  std::vector<CallbackSpec> m_callbacks;
};

class FlowTable {
public:
  FlowTable(int max_flows);

  int occupied() const;
  FlowId id_at_index(int index) const;

  FlowId put(std::string fmatch);
  Flow& get(FlowId id);
  bool exists(FlowId id);
  void clear(FlowId id);

  FlowId candidate_for_eviction();

private:
  const int m_max_flows;
  const int m_mask;
  std::vector<Flow> m_table;
  long long m_id_counter;
  int m_occupied;
};

class FlowTagIndexer {
public:
  void index_flow_tag(FlowId id, FlowTag tag);
  std::vector<FlowId> invalidate(FlowTag tag);
  std::vector<FlowId> flows_for_tag(FlowTag tag) const;
  void remove_flow(FlowId id);
  int tag_count() const;

private:
  std::unordered_map<FlowTag, std::unordered_set<FlowId>> m_tags_to_flows;
  std::unordered_map<FlowId, std::vector<FlowTag>> m_flows_to_tags;
};

int leading_zeros(int input);
int next_pos_power_of_two(int input);


#endif // _NATIVE_FLOW_CONTROLLER_H_
