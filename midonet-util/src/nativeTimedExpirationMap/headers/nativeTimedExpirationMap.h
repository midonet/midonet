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
#ifndef _NATIVE_TIMED_EXPIRY_MAP_H_
#define _NATIVE_TIMED_EXPIRY_MAP_H_

#include <limits.h>
#include <unordered_map>
#include <queue>

template<class T>
class option {
public:
  option(T value): m_value(value), m_set(true) {}

  T value() const { return m_value; }
  operator bool () const { return m_set; }

  static option<T> null_opt;
private:
  T m_value;
  bool m_set;

  option(): m_set(false) {}
};

template<class T> option<T> option<T>::null_opt = option();

class Metadata {
public:
  Metadata()
    : m_value(), m_ref_count(-1), m_expiration(LONG_MAX) {}
  Metadata(std::string value, int ref_count, long expiration)
    : m_value(value), m_ref_count(ref_count), m_expiration(expiration) {}

  std::string value() const { return m_value; }
  void set_value(std::string value) { m_value = value; }

  int ref_count() const { return m_ref_count; }
  int inc_if_greater_than(int than) {
    if (m_ref_count > than) { m_ref_count++; }
    return m_ref_count;
  }
  int dec_and_get() { return --m_ref_count; }

  long expiration() const { return m_expiration; }
  void set_expiration(long expiration) { m_expiration = expiration; }
private:
  std::string m_value;
  int m_ref_count;
  long m_expiration;
};

class NativeTimedExpirationMap {
public:
  const option<std::string> put_and_ref(const std::string key, const std::string value);
  int put_if_absent_and_ref(const std::string key, const std::string value);
  const option<std::string> get(const std::string key) const;
  const option<std::string> ref(const std::string key);
  int ref_and_get_count(const std::string key);
  int ref_count(const std::string key) const;
  const option<std::string> unref(const std::string key, long expire_in,
                                  long current_time_millis);

  class Iterator {
  public:
    virtual bool at_end() const = 0;
    virtual void next() = 0;
    virtual std::string cur_key() const = 0;
    virtual std::string cur_value() const = 0;
  };

  NativeTimedExpirationMap::Iterator* iterator() const;
  NativeTimedExpirationMap::Iterator* obliterate(long current_time_millis);

  typedef std::queue<std::pair<std::string,long>> ExpirationQueue;
private:
  std::unordered_map<std::string, Metadata> ref_count_map;
  std::unordered_map<long, ExpirationQueue> expiring;

  class AllEntriesIterator : public Iterator {
  public:
    AllEntriesIterator(const std::unordered_map<std::string, Metadata>& map);
    bool at_end() const;
    void next();
    std::string cur_key() const;
    std::string cur_value() const;

  private:

    std::unordered_map<std::string, Metadata>::const_iterator iterator;
    const std::unordered_map<std::string, Metadata>::const_iterator end;

    friend class NativeTimedExpirationMap;
  };


  class ObliterationIterator : public Iterator {
  public:
    ObliterationIterator(std::unordered_map<long, ExpirationQueue>& expiring,
                         std::unordered_map<std::string, Metadata>& ref_count_map,
                         long current_time_millis);
    bool at_end() const;
    void next();
    std::string cur_key() const;
    std::string cur_value() const;

  private:
    void progress_iterator();

    std::unordered_map<long, ExpirationQueue>& expiring;
    std::unordered_map<std::string, Metadata>& ref_count_map;
    long current_time_millis;
    std::unordered_map<long, ExpirationQueue>::iterator queue_iterator;

    friend class NativeTimedExpirationMap;
  };
};

#endif // _NATIVE_TIMED_EXPIRY_MAP_H_
