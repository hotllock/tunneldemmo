#pragma once

#include <cstdint>
#include <cstring>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <android/log.h>

#define LOG_TAG "ProxyConnector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define PROXY_CONNECT_TIMEOUT_MS 5000
#define MAX_PROXY_RW_SIZE (64 * 1024)

class ProxyConnector {
public:
    static int connect_to_proxy(const char *proxy_host, int proxy_port);
    static int forward_to_proxy(int proxy_fd, const uint8_t *data, size_t len);
    static int read_from_proxy(int proxy_fd, uint8_t *buffer, size_t max_len);
    static void close_proxy_connection(int proxy_fd);
    static int set_socket_timeout(int fd, int timeout_ms);

private:
    static bool is_http_request(const uint8_t *data, size_t len);
};

inline int ProxyConnector::connect_to_proxy(const char *proxy_host, int proxy_port) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        LOGE("Failed to create proxy socket: %s", strerror(errno));
        return -1;
    }

    int flags = fcntl(sock, F_GETFL, 0);
    fcntl(sock, F_SETFL, flags | O_NONBLOCK);

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(proxy_port));
    inet_pton(AF_INET, proxy_host, &addr.sin_addr);

    int ret = connect(sock, (struct sockaddr *)&addr, sizeof(addr));
    if (ret < 0) {
        if (errno != EINPROGRESS) {
            LOGE("Proxy connect failed: %s", strerror(errno));
            close(sock);
            return -1;
        }
        struct timeval tv;
        tv.tv_sec = PROXY_CONNECT_TIMEOUT_MS / 1000;
        tv.tv_usec = (PROXY_CONNECT_TIMEOUT_MS % 1000) * 1000;
        fd_set fdset;
        FD_ZERO(&fdset);
        FD_SET(sock, &fdset);
        ret = select(sock + 1, nullptr, &fdset, nullptr, &tv);
        if (ret <= 0) {
            LOGE("Proxy connect timeout/error: %s",
                 ret == 0 ? "timeout" : strerror(errno));
            close(sock);
            return -1;
        }
        int so_error = 0;
        socklen_t len = sizeof(so_error);
        getsockopt(sock, SOL_SOCKET, SO_ERROR, &so_error, &len);
        if (so_error != 0) {
            LOGE("Proxy connect SO_ERROR: %s", strerror(so_error));
            close(sock);
            return -1;
        }
    }

    flags = fcntl(sock, F_GETFL, 0);
    fcntl(sock, F_SETFL, flags & ~O_NONBLOCK);
    set_socket_timeout(sock, 30000);
    LOGI("Connected to proxy %s:%d (fd=%d)", proxy_host, proxy_port, sock);
    return sock;
}

inline int ProxyConnector::forward_to_proxy(int proxy_fd, const uint8_t *data, size_t len) {
    if (proxy_fd < 0 || data == nullptr || len == 0) return -1;
    size_t total_written = 0;
    while (total_written < len) {
        ssize_t n = write(proxy_fd, data + total_written, len - total_written);
        if (n <= 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) continue;
            LOGE("Forward write error: %s", strerror(errno));
            return -1;
        }
        total_written += n;
    }
    return static_cast<int>(total_written);
}

inline int ProxyConnector::read_from_proxy(int proxy_fd, uint8_t *buffer, size_t max_len) {
    if (proxy_fd < 0 || buffer == nullptr || max_len == 0) return -1;
    ssize_t n = read(proxy_fd, buffer, max_len);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
        LOGE("Proxy read error: %s", strerror(errno));
        return -1;
    }
    return static_cast<int>(n);
}

inline void ProxyConnector::close_proxy_connection(int proxy_fd) {
    if (proxy_fd >= 0) {
        shutdown(proxy_fd, SHUT_RDWR);
        close(proxy_fd);
    }
}

inline int ProxyConnector::set_socket_timeout(int fd, int timeout_ms) {
    struct timeval tv;
    tv.tv_sec = timeout_ms / 1000;
    tv.tv_usec = (timeout_ms % 1000) * 1000;
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)) < 0) return -1;
    if (setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv)) < 0) return -1;
    return 0;
}

inline bool ProxyConnector::is_http_request(const uint8_t *data, size_t len) {
    if (len < 4) return false;
    const char *methods[] = {"GET ", "POST", "PUT ", "DEL ", "HEAD", "PATC",
                             "OPT ", "CONN", "TRAC"};
    for (const auto *m : methods) {
        if (std::memcmp(data, m, 4) == 0) return true;
    }
    return false;
}
