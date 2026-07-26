#pragma once

#include <cstdint>
#include <cstring>
#include <unordered_map>
#include <vector>
#include <mutex>
#include <chrono>
#include <unistd.h>
#include <android/log.h>

#define MAX_TCP_BUFFER (128 * 1024)
#define TCP_CLEANUP_TIMEOUT_MS 120000
#define TCP_IDLE_TIMEOUT_MS 30000

struct ConnectionKey {
    uint32_t src_ip;
    uint32_t dst_ip;
    uint16_t src_port;
    uint16_t dst_port;
    uint8_t protocol;

    bool operator==(const ConnectionKey &o) const {
        return src_ip == o.src_ip && dst_ip == o.dst_ip &&
               src_port == o.src_port && dst_port == o.dst_port &&
               protocol == o.protocol;
    }
};

struct ConnectionKeyHash {
    size_t operator()(const ConnectionKey &k) const {
        return std::hash<uint32_t>()(k.src_ip) ^
               std::hash<uint32_t>()(k.dst_ip) ^
               std::hash<uint16_t>()(k.src_port) ^
               std::hash<uint16_t>()(k.dst_port) ^
               std::hash<uint8_t>()(k.protocol);
    }
};

enum class ConnState : uint8_t { SYN_SENT, ESTABLISHED, FIN_WAIT, CLOSED };

struct TcpConnection {
    ConnectionKey key;
    ConnState state = ConnState::SYN_SENT;
    uint32_t seq_no = 0;
    uint32_t ack_no = 0;
    std::vector<uint8_t> reassembly_buffer;
    size_t reassembled_len = 0;
    bool proxy_connected = false;
    int proxy_sock_fd = -1;
    int tun_fd = -1;
    std::chrono::steady_clock::time_point last_activity;
    uint64_t bytes_received = 0;
    uint64_t bytes_sent = 0;
    TcpConnection() = default;
};

class TcpReassembler {
public:
    TcpReassembler() = default;
    ~TcpReassembler() { cleanup_all(); }

    bool add_connection(const ConnectionKey &key, uint32_t seq, uint32_t ack) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (connections_.find(key) != connections_.end()) return false;
        TcpConnection conn;
        conn.key = key;
        conn.seq_no = seq;
        conn.ack_no = ack;
        conn.state = ConnState::SYN_SENT;
        conn.last_activity = std::chrono::steady_clock::now();
        connections_[key] = conn;
        return true;
    }

    bool remove_connection(const ConnectionKey &key) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = connections_.find(key);
        if (it == connections_.end()) return false;
        if (it->second.proxy_sock_fd >= 0) {
            close(it->second.proxy_sock_fd);
        }
        connections_.erase(it);
        return true;
    }

    TcpConnection* get_connection(const ConnectionKey &key) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = connections_.find(key);
        if (it != connections_.end()) {
            it->second.last_activity = std::chrono::steady_clock::now();
            return &it->second;
        }
        return nullptr;
    }

    size_t feed_tcp_data(const ConnectionKey &key, const uint8_t *data,
                          size_t len, uint32_t seq, bool is_fin) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = connections_.find(key);
        if (it == connections_.end()) return 0;
        auto &conn = it->second;
        conn.last_activity = std::chrono::steady_clock::now();
        if (conn.state == ConnState::SYN_SENT) {
            conn.state = ConnState::ESTABLISHED;
        }
        if (len > 0) {
            conn.reassembly_buffer.insert(conn.reassembly_buffer.end(),
                                           data, data + len);
            conn.reassembled_len += len;
            conn.bytes_received += len;
            if (conn.reassembly_buffer.size() > MAX_TCP_BUFFER) {
                conn.reassembly_buffer.erase(
                    conn.reassembly_buffer.begin(),
                    conn.reassembly_buffer.begin() +
                        (conn.reassembly_buffer.size() - MAX_TCP_BUFFER));
            }
        }
        if (is_fin) {
            conn.state = ConnState::FIN_WAIT;
        }
        return len;
    }

    void cleanup_stale() {
        std::lock_guard<std::mutex> lock(mutex_);
        auto now = std::chrono::steady_clock::now();
        for (auto it = connections_.begin(); it != connections_.end(); ) {
            auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                now - it->second.last_activity).count();
            if (elapsed > TCP_IDLE_TIMEOUT_MS ||
                it->second.state == ConnState::FIN_WAIT) {
                if (it->second.proxy_sock_fd >= 0) {
                    close(it->second.proxy_sock_fd);
                }
                it = connections_.erase(it);
            } else {
                ++it;
            }
        }
    }

    void cleanup_all() {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto &pair : connections_) {
            if (pair.second.proxy_sock_fd >= 0) {
                close(pair.second.proxy_sock_fd);
            }
        }
        connections_.clear();
    }

    size_t size() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return connections_.size();
    }

private:
    mutable std::mutex mutex_;
    std::unordered_map<ConnectionKey, TcpConnection, ConnectionKeyHash> connections_;
};
