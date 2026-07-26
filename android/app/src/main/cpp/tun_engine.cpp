#include "tcp_reassembler.h"
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <thread>
#include <atomic>
#include <memory>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <chrono>
#include <poll.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <netinet/udp.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <cerrno>
#include <jni.h>
#include <android/log.h>

#define LOG_TAG "TunEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define TUN_MTU 1500
#define BUF_SIZE 65536
#define MAX_CONNS 4096
#define MAX_UDP_CONNS 1024
#define CLEANUP_MS 15000
#define IDLE_TIMEOUT_MS 120000
#define UDP_IDLE_TIMEOUT_MS 60000
#define UDP_POLL_MAX 64

#pragma pack(push, 1)
struct ip4_hdr {
    uint8_t  ver_ihl;
    uint8_t  tos;
    uint16_t total_len;
    uint16_t id;
    uint16_t frag_off;
    uint8_t  ttl;
    uint8_t  proto;
    uint16_t csum;
    uint32_t saddr;
    uint32_t daddr;
};

struct ip6_hdr {
    uint32_t vfc_flow; // version(4), tc(8), flow(20)
    uint16_t payload_len;
    uint8_t  next_hdr;
    uint8_t  hop_limit;
    uint8_t  saddr[16];
    uint8_t  daddr[16];
};

struct tcp_hdr {
    uint16_t src_port;
    uint16_t dst_port;
    uint32_t seq;
    uint32_t ack;
    uint16_t doff_flags;
    uint16_t window;
    uint16_t csum;
    uint16_t urg;
};

struct udp_hdr {
    uint16_t src_port;
    uint16_t dst_port;
    uint16_t length;
    uint16_t csum;
};
#pragma pack(pop)

enum : uint8_t {
    TF_FIN = 0x01, TF_SYN = 0x02, TF_RST = 0x04,
    TF_PSH = 0x08, TF_ACK = 0x10,
};

static std::atomic<bool> g_run{false};
static std::atomic<int> g_tun_fd{-1};
static int g_proxy_port = 8080;
static std::atomic<uint64_t> g_stats[6] = {0,0,0,0,0,0};

struct Conn {
    uint8_t  ver; // 4 or 6
    uint8_t  saddr[16], daddr[16];
    uint16_t sport, dport;
    int proxy_fd;
    uint32_t cli_seq, cli_ack, srv_seq, srv_ack;
    uint64_t last_seen;
    bool fin_recvd, fin_sent;
};

using ConnMap = std::unordered_map<std::string, std::unique_ptr<Conn>>;
static ConnMap g_conns;
static std::mutex g_mtx;

// ---- UDP forwarding ----

struct UdpConn {
    uint8_t ver;
    uint8_t saddr[16], daddr[16];
    uint16_t sport, dport;
    int udp_fd;
    struct sockaddr_storage peer_addr;
    socklen_t peer_len;
    uint64_t last_seen;
};

using UdpConnMap = std::unordered_map<std::string, std::unique_ptr<UdpConn>>;
static UdpConnMap g_udp_conns;
static std::mutex g_udp_mtx;

static std::string udp_key4(uint32_t sa, uint32_t da, uint16_t sp, uint16_t dp) {
    char k[64];
    snprintf(k, sizeof(k), "u4.%08x.%08x.%04x.%04x", sa, da, sp, dp);
    return std::string(k);
}

static std::string udp_key6(const uint8_t *sa, const uint8_t *da,
                              uint16_t sp, uint16_t dp) {
    char k[128];
    snprintf(k, sizeof(k), "u6.%08x%08x%08x%08x.%08x%08x%08x%08x.%04x.%04x",
             ntohl(*(uint32_t*)(sa+0)), ntohl(*(uint32_t*)(sa+4)),
             ntohl(*(uint32_t*)(sa+8)), ntohl(*(uint32_t*)(sa+12)),
             ntohl(*(uint32_t*)(da+0)), ntohl(*(uint32_t*)(da+4)),
             ntohl(*(uint32_t*)(da+8)), ntohl(*(uint32_t*)(da+12)),
             sp, dp);
    return std::string(k);
}

// Cached JavaVM for calling back to protectSocket
static JavaVM *g_jvm = nullptr;

static std::string conn_key4(uint32_t sa, uint32_t da, uint16_t sp, uint16_t dp) {
    char k[64];
    snprintf(k, sizeof(k), "4.%08x.%08x.%04x.%04x", sa, da, sp, dp);
    return std::string(k);
}

static std::string conn_key6(const uint8_t *sa, const uint8_t *da,
                              uint16_t sp, uint16_t dp) {
    char k[128];
    snprintf(k, sizeof(k), "6.%08x%08x%08x%08x.%08x%08x%08x%08x.%04x.%04x",
             ntohl(*(uint32_t*)(sa+0)), ntohl(*(uint32_t*)(sa+4)),
             ntohl(*(uint32_t*)(sa+8)), ntohl(*(uint32_t*)(sa+12)),
             ntohl(*(uint32_t*)(da+0)), ntohl(*(uint32_t*)(da+4)),
             ntohl(*(uint32_t*)(da+8)), ntohl(*(uint32_t*)(da+12)),
             sp, dp);
    return std::string(k);
}

static inline int ip4_hdrlen(const ip4_hdr *ip) { return (ip->ver_ihl & 0x0f) * 4; }
static inline int tcp_hdrlen(const tcp_hdr *tcp) { return ((tcp->doff_flags >> 12) & 0x0f) * 4; }
static inline uint16_t tcp_flags_val(const tcp_hdr *tcp) { return ntohs(tcp->doff_flags) & 0x3f; }

// Internet checksum (big-endian byte order)
static uint16_t inet_csum(const void *buf, size_t len, uint32_t init = 0) {
    uint32_t s = init;
    auto *p = (const uint8_t *)buf;
    for (size_t i = 0; i < len / 2; i++)
        s += ((uint16_t)p[i*2] << 8) | p[i*2 + 1];
    if (len & 1)
        s += (uint16_t)p[len - 1] << 8;
    while (s >> 16) s = (s & 0xffff) + (s >> 16);
    return (uint16_t)~s;
}

// TCP pseudo-header sum for IPv4
static uint32_t tcp4_psum(uint32_t saddr_n, uint32_t daddr_n, uint16_t tcp_len_n) {
    uint8_t *s = (uint8_t *)&saddr_n;
    uint8_t *d = (uint8_t *)&daddr_n;
    uint32_t sum = 0;
    sum += ((uint16_t)s[0] << 8) | s[1];
    sum += ((uint16_t)s[2] << 8) | s[3];
    sum += ((uint16_t)d[0] << 8) | d[1];
    sum += ((uint16_t)d[2] << 8) | d[3];
    sum += htons(IPPROTO_TCP);
    sum += tcp_len_n;
    return sum;
}

// TCP pseudo-header sum for IPv6 (128-bit addresses)
static uint32_t tcp6_psum(const uint8_t *saddr, const uint8_t *daddr, uint16_t tcp_len_n) {
    uint32_t sum = 0;
    for (int i = 0; i < 16; i += 2)
        sum += ((uint16_t)saddr[i] << 8) | saddr[i+1];
    for (int i = 0; i < 16; i += 2)
        sum += ((uint16_t)daddr[i] << 8) | daddr[i+1];
    sum += htons(tcp_len_n);
    sum += htons(IPPROTO_TCP);
    return sum;
}

static void set_tcp_csum(struct tcp_hdr *tcp, int tcp_len, uint32_t saddr_n, uint32_t daddr_n) {
    tcp->csum = 0;
    uint32_t sum = tcp4_psum(saddr_n, daddr_n, htons((uint16_t)tcp_len));
    sum += inet_csum(tcp, tcp_len);
    while (sum >> 16) sum = (sum & 0xffff) + (sum >> 16);
    tcp->csum = (uint16_t)~sum;
}

static void set_tcp6_csum(struct tcp_hdr *tcp, int tcp_len,
                           const uint8_t *saddr, const uint8_t *daddr) {
    tcp->csum = 0;
    uint32_t sum = tcp6_psum(saddr, daddr, htons((uint16_t)tcp_len));
    sum += inet_csum(tcp, tcp_len);
    while (sum >> 16) sum = (sum & 0xffff) + (sum >> 16);
    tcp->csum = (uint16_t)~sum;
}

static int write_tun(int fd, const void *buf, size_t len) {
    if (fd < 0) return -1;
    ssize_t n = write(fd, buf, len);
    if (n < 0 && errno != EAGAIN) LOGE("tun write: %s", strerror(errno));
    return (int)n;
}

// Protect a native socket from VPN loop via JNI call to Kotlin
static bool protect_socket(int fd) {
    if (fd < 0 || !g_jvm) return false;
    JNIEnv *env = nullptr;
    bool need_detach = false;
    int get_ret = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (get_ret == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
        need_detach = true;
    }
    if (!env) return false;

    jclass cls = env->FindClass("com/tunnel/demo/tunneldemo/native/TunEngineBridge");
    if (!cls) { if (need_detach) g_jvm->DetachCurrentThread(); return false; }
    jmethodID mid = env->GetStaticMethodID(cls, "nativeProtectSocket", "(I)Z");
    if (!mid) { if (need_detach) g_jvm->DetachCurrentThread(); return false; }
    jboolean result = env->CallStaticBooleanMethod(cls, mid, (jint)fd);
    env->DeleteLocalRef(cls);
    if (need_detach) g_jvm->DetachCurrentThread();
    return result == JNI_TRUE;
}

static int tcp_connect(const char *host, int port, int timeout_ms) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    inet_pton(AF_INET, host, &addr.sin_addr);

    int r = connect(fd, (struct sockaddr *)&addr, sizeof(addr));
    if (r < 0 && errno != EINPROGRESS) { close(fd); return -1; }

    struct pollfd pfd = {fd, POLLOUT, 0};
    r = poll(&pfd, 1, timeout_ms);
    if (r <= 0) { close(fd); return -1; }

    int err = 0; socklen_t el = sizeof(err);
    getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &el);
    if (err) { close(fd); return -1; }

    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) & ~O_NONBLOCK);
    struct timeval tv;
    tv.tv_sec = timeout_ms / 1000; tv.tv_usec = (timeout_ms % 1000) * 1000;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    return fd;
}

static int proxy_connect(const char *target_host, int target_port) {
    char buf[512];
    int n = snprintf(buf, sizeof(buf), "CONNECT %s:%d HTTP/1.1\r\nHost: %s:%d\r\n\r\n",
                     target_host, target_port, target_host, target_port);

    int fd = tcp_connect("127.0.0.1", g_proxy_port, 5000);
    if (fd < 0) return -1;

    if (write(fd, buf, n) != n) { close(fd); return -1; }

    char resp[512];
    n = read(fd, resp, sizeof(resp) - 1);
    if (n <= 0) { close(fd); return -1; }
    resp[n] = 0;
    if (!strstr(resp, "200")) { close(fd); return -1; }
    return fd;
}

static void forward_to_proxy(Conn *c, const uint8_t *data, uint16_t len, int tun_fd) {
    if (c->proxy_fd < 0 || len == 0) return;

    int w = (int)write(c->proxy_fd, data, len);
    if (w < 0) {
        if (errno == EPIPE || errno == ECONNRESET) {
            close(c->proxy_fd);
            c->proxy_fd = -1;
        }
        return;
    }

    // Non-blocking read from proxy
    uint8_t rbuf[BUF_SIZE];
    struct pollfd pfd = {c->proxy_fd, POLLIN, 0};
    if (poll(&pfd, 1, 10) > 0 && (pfd.revents & POLLIN)) {
        int n = (int)read(c->proxy_fd, rbuf, sizeof(rbuf));
        if (n > 0) {
            // Packet type selection based on IP version
            uint32_t saddr_n = (c->ver == 4) ? htonl(*(uint32_t*)c->daddr) : 0;
            uint32_t daddr_n = (c->ver == 4) ? htonl(*(uint32_t*)c->saddr) : 0;
            uint16_t sport_n = htons(c->dport);
            uint16_t dport_n = htons(c->sport);

            if (c->ver == 4) {
                uint16_t tcp_len = sizeof(tcp_hdr) + n;
                uint16_t total = sizeof(ip4_hdr) + tcp_len;
                auto *buf2 = (uint8_t *)malloc(total);
                if (!buf2) return;
                auto *ip = (ip4_hdr *)buf2;
                auto *tcp = (tcp_hdr *)(buf2 + sizeof(ip4_hdr));
                memset(ip, 0, sizeof(ip4_hdr));
                ip->ver_ihl = 0x45;
                ip->total_len = htons(total);
                ip->ttl = 64;
                ip->proto = IPPROTO_TCP;
                ip->saddr = saddr_n;
                ip->daddr = daddr_n;
                tcp->src_port = sport_n;
                tcp->dst_port = dport_n;
                tcp->seq = htonl(c->srv_seq);
                tcp->ack = htonl(c->srv_ack);
                tcp->doff_flags = htons((5 << 12) | TF_PSH | TF_ACK);
                tcp->window = htons(65535);
                memcpy(buf2 + sizeof(ip4_hdr) + sizeof(tcp_hdr), rbuf, n);
                ip->csum = inet_csum(ip, sizeof(ip4_hdr));
                set_tcp_csum(tcp, tcp_len, ip->saddr, ip->daddr);
                write_tun(tun_fd, buf2, total);
                free(buf2);
            } else {
                uint16_t tcp_len = sizeof(tcp_hdr) + n;
                uint16_t total = sizeof(ip6_hdr) + tcp_len;
                auto *buf2 = (uint8_t *)malloc(total);
                if (!buf2) return;
                auto *ip = (ip6_hdr *)buf2;
                auto *tcp = (tcp_hdr *)(buf2 + sizeof(ip6_hdr));
                memset(ip, 0, sizeof(ip6_hdr));
                ip->vfc_flow = htonl(0x60000000);
                ip->payload_len = htons(tcp_len);
                ip->next_hdr = IPPROTO_TCP;
                ip->hop_limit = 64;
                memcpy(ip->saddr, c->daddr, 16);
                memcpy(ip->daddr, c->saddr, 16);
                tcp->src_port = sport_n;
                tcp->dst_port = dport_n;
                tcp->seq = htonl(c->srv_seq);
                tcp->ack = htonl(c->srv_ack);
                tcp->doff_flags = htons((5 << 12) | TF_PSH | TF_ACK);
                tcp->window = htons(65535);
                memcpy(buf2 + sizeof(ip6_hdr) + sizeof(tcp_hdr), rbuf, n);
                set_tcp6_csum(tcp, tcp_len, ip->saddr, ip->daddr);
                write_tun(tun_fd, buf2, total);
                free(buf2);
            }
            c->srv_seq += n;
        } else if (n == 0) {
            close(c->proxy_fd);
            c->proxy_fd = -1;
        }
    }
}

static int parse_ip4(const uint8_t *pkt, uint16_t len,
                      uint8_t **tcp_pkt, int *tcp_len,
                      uint32_t *saddr_n, uint32_t *daddr_n) {
    if (len < sizeof(ip4_hdr)) return -1;
    auto *ip = (ip4_hdr *)pkt;
    int hl = ip4_hdrlen(ip);
    if (hl < 20) return -1;
    uint16_t total = ntohs(ip->total_len);
    if (total > len) total = len;
    if (total < hl + (int)sizeof(tcp_hdr)) return -1;
    if (ip->proto != IPPROTO_TCP) return -1;
    *tcp_pkt = (uint8_t *)pkt + hl;
    *tcp_len = total - hl;
    *saddr_n = ip->saddr;
    *daddr_n = ip->daddr;
    return 4;
}

static int parse_ip6(const uint8_t *pkt, uint16_t len,
                      uint8_t **tcp_pkt, int *tcp_len,
                      uint8_t *saddr, uint8_t *daddr) {
    if (len < sizeof(ip6_hdr)) return -1;
    auto *ip = (ip6_hdr *)pkt;

    // Walk extension headers to find TCP
    uint8_t next = ip->next_hdr;
    int offset = sizeof(ip6_hdr);
    int remaining = ntohs(ip->payload_len);

    while (next != IPPROTO_TCP && next != 59) {
        if (remaining < 2 || offset + 2 > len) return -1;
        uint8_t ext_len = pkt[offset + 1];
        switch (next) {
            case 0: case 43: case 44: case 50: case 51: case 60:
            case 135: case 139: case 140:
                if (ext_len == 0) ext_len = 8;
                offset += (ext_len + 1) * 8;
                remaining -= (ext_len + 1) * 8;
                next = pkt[offset - (ext_len + 1) * 8];
                break;
            default:
                // Unknown extension, try treating next byte as next header
                offset += 8;
                remaining -= 8;
                next = pkt[offset - 8];
                break;
        }
    }

    if (next != IPPROTO_TCP) return -1;
    if (offset + (int)sizeof(tcp_hdr) > len) return -1;

    *tcp_pkt = (uint8_t *)pkt + offset;
    *tcp_len = len - offset;
    memcpy(saddr, ip->saddr, 16);
    memcpy(daddr, ip->daddr, 16);
    return 6;
}

static void handle_conn_pkt(Conn *c, const tcp_hdr *tcp, int tcp_len,
                             uint16_t flags, uint8_t version, int tun_fd) {
    int pay_len = tcp_len - tcp_hdrlen(tcp);
    const uint8_t *pay = (pay_len > 0 && tcp_hdrlen(tcp) <= tcp_len)
        ? ((const uint8_t *)tcp + tcp_hdrlen(tcp)) : nullptr;

    c->last_seen = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();

    if (flags & TF_RST) {
        if (c->proxy_fd >= 0) close(c->proxy_fd);
        return;
    }

    if (flags & TF_FIN) {
        c->fin_recvd = true;
        if (pay_len > 0 && c->proxy_fd >= 0)
            forward_to_proxy(c, pay, pay_len, tun_fd);
        if (c->proxy_fd >= 0)
            shutdown(c->proxy_fd, SHUT_WR);
        if (c->fin_sent || c->proxy_fd < 0) {
            if (c->proxy_fd >= 0) close(c->proxy_fd);
            c->proxy_fd = -1;
        }
        return;
    }

    c->cli_ack = ntohl(tcp->ack);

    if (pay_len > 0)
        forward_to_proxy(c, pay, pay_len, tun_fd);
}

// Write a UDP response packet to the TUN interface
static void write_udp_response(int tun_fd, const uint8_t *payload, uint16_t pay_len,
                                const uint8_t *saddr, const uint8_t *daddr,
                                uint16_t sport, uint16_t dport, uint8_t version) {
    if (version == 4) {
        uint16_t udp_len = sizeof(udp_hdr) + pay_len;
        uint16_t total = sizeof(ip4_hdr) + udp_len;
        auto *buf = (uint8_t *)malloc(total);
        if (!buf) return;
        memset(buf, 0, total);
        auto *ip = (ip4_hdr *)buf;
        auto *udp = (udp_hdr *)(buf + sizeof(ip4_hdr));
        ip->ver_ihl = 0x45;
        ip->total_len = htons(total);
        ip->ttl = 64;
        ip->proto = IPPROTO_UDP;
        ip->saddr = *(uint32_t*)saddr;
        ip->daddr = *(uint32_t*)daddr;
        udp->src_port = htons(sport);
        udp->dst_port = htons(dport);
        udp->length = htons(udp_len);
        udp->csum = 0; // UDP over IPv4: checksum is optional (0 = no checksum)
        memcpy(buf + sizeof(ip4_hdr) + sizeof(udp_hdr), payload, pay_len);
        ip->csum = inet_csum(ip, sizeof(ip4_hdr));
        write_tun(tun_fd, buf, total);
        free(buf);
    } else {
        uint16_t udp_len = sizeof(udp_hdr) + pay_len;
        uint16_t total = sizeof(ip6_hdr) + udp_len;
        auto *buf = (uint8_t *)malloc(total);
        if (!buf) return;
        memset(buf, 0, total);
        auto *ip = (ip6_hdr *)buf;
        auto *udp = (udp_hdr *)(buf + sizeof(ip6_hdr));
        ip->vfc_flow = htonl(0x60000000);
        ip->payload_len = htons(udp_len);
        ip->next_hdr = IPPROTO_UDP;
        ip->hop_limit = 64;
        memcpy(ip->saddr, saddr, 16);
        memcpy(ip->daddr, daddr, 16);
        udp->src_port = htons(sport);
        udp->dst_port = htons(dport);
        udp->length = htons(udp_len);
        // UDP checksum is mandatory for IPv6, but we set 0 for simplicity
        // (some networks accept it, others will drop; proper impl needs pseudo-header)
        // TODO: compute proper UDPv6 checksum
        udp->csum = 0;
        memcpy(buf + sizeof(ip6_hdr) + sizeof(udp_hdr), payload, pay_len);
        write_tun(tun_fd, buf, total);
        free(buf);
    }
}

// Handle an incoming UDP packet from the TUN interface
static void handle_udp_packet(uint8_t *pkt, uint16_t len, int tun_fd,
                              uint8_t version, uint8_t *saddr, uint8_t *daddr,
                              udp_hdr *udp) {
    uint16_t sport = ntohs(udp->src_port);
    uint16_t dport = ntohs(udp->dst_port);
    uint16_t udp_len = ntohs(udp->length);
    if (udp_len < sizeof(udp_hdr) || udp_len > len) return;
    uint16_t pay_len = udp_len - sizeof(udp_hdr);
    uint8_t *payload = (uint8_t *)udp + sizeof(udp_hdr);

    g_stats[0]++;
    g_stats[1] += udp_len;

    std::string key;
    if (version == 4) {
        uint32_t sa = ntohl(*(uint32_t*)saddr);
        uint32_t da = ntohl(*(uint32_t*)daddr);
        key = udp_key4(sa, da, sport, dport);
    } else {
        key = udp_key6(saddr, daddr, sport, dport);
    }

    UdpConn *uc = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_udp_mtx);
        auto it = g_udp_conns.find(key);
        if (it != g_udp_conns.end()) uc = it->second.get();
    }

    if (uc) {
        // Existing flow: send payload to real destination
        uc->last_seen = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        sendto(uc->udp_fd, payload, pay_len, 0,
               (struct sockaddr *)&uc->peer_addr, uc->peer_len);
        return;
    }

    // New UDP flow: create real socket
    int udp_fd = socket(version == 4 ? AF_INET : AF_INET6, SOCK_DGRAM, 0);
    if (udp_fd < 0) return;

    fcntl(udp_fd, F_SETFL, fcntl(udp_fd, F_GETFL) | O_NONBLOCK);

    // Protect the socket from VPN loop
    protect_socket(udp_fd);

    // Prepare peer address for sendto
    struct sockaddr_storage peer;
    socklen_t peer_len;
    memset(&peer, 0, sizeof(peer));
    if (version == 4) {
        auto *sin = (struct sockaddr_in *)&peer;
        sin->sin_family = AF_INET;
        sin->sin_port = htons(dport);
        sin->sin_addr.s_addr = *(uint32_t*)daddr;
        peer_len = sizeof(struct sockaddr_in);
    } else {
        auto *sin6 = (struct sockaddr_in6 *)&peer;
        sin6->sin6_family = AF_INET6;
        sin6->sin6_port = htons(dport);
        memcpy(&sin6->sin6_addr, daddr, 16);
        peer_len = sizeof(struct sockaddr_in6);
    }

    // Store connection
    auto conn = std::make_unique<UdpConn>();
    conn->ver = version;
    memcpy(conn->saddr, saddr, 16);
    memcpy(conn->daddr, daddr, 16);
    conn->sport = sport;
    conn->dport = dport;
    conn->udp_fd = udp_fd;
    memcpy(&conn->peer_addr, &peer, sizeof(peer));
    conn->peer_len = peer_len;
    conn->last_seen = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();

    {
        std::lock_guard<std::mutex> lk(g_udp_mtx);
        // Evict oldest if at limit
        if (g_udp_conns.size() >= MAX_UDP_CONNS) {
            uint64_t oldest_ts = UINT64_MAX;
            std::string oldest_key;
            for (auto &p : g_udp_conns) {
                if (p.second->last_seen < oldest_ts) {
                    oldest_ts = p.second->last_seen;
                    oldest_key = p.first;
                }
            }
            if (!oldest_key.empty()) {
                close(g_udp_conns[oldest_key]->udp_fd);
                g_udp_conns.erase(oldest_key);
            }
        }
        g_udp_conns[key] = std::move(conn);
    }

    // Send the first payload
    sendto(udp_fd, payload, pay_len, 0, (struct sockaddr *)&peer, peer_len);
}

static void process_packet(uint8_t *pkt, uint16_t len, int tun_fd) {
    uint8_t *tcp_raw = nullptr;
    int tcp_raw_len = 0;
    uint32_t saddr4_n = 0, daddr4_n = 0;
    uint8_t saddr6[16], daddr6[16];
    int version;

    // Determine IP version from first nibble
    uint8_t ip_ver = (pkt[0] >> 4) & 0x0f;
    if (ip_ver == 4) {
        // Handle IPv4
        if (len < sizeof(ip4_hdr)) return;
        auto *ip = (ip4_hdr *)pkt;
        int hl = ip4_hdrlen(ip);
        if (hl < 20 || len < hl) return;
        uint16_t total = ntohs(ip->total_len);
        if (total > len) total = len;

        if (ip->proto == IPPROTO_TCP) {
            if (total < hl + (int)sizeof(tcp_hdr)) return;
            version = 4;
            tcp_raw = (uint8_t *)pkt + hl;
            tcp_raw_len = total - hl;
            saddr4_n = ip->saddr;
            daddr4_n = ip->daddr;
        } else if (ip->proto == IPPROTO_UDP && total >= hl + (int)sizeof(udp_hdr)) {
            uint8_t saddr_buf[16] = {0};
            uint8_t daddr_buf[16] = {0};
            // Store IPv4 address at start (handle_udp_packet reads first 4 bytes for version==4)
            memcpy(saddr_buf, &ip->saddr, 4);
            memcpy(daddr_buf, &ip->daddr, 4);
            handle_udp_packet(pkt, len, tun_fd, 4,
                              saddr_buf, daddr_buf,
                              (udp_hdr*)(pkt + hl));
            return;
        } else {
            return; // not TCP or UDP
        }
    } else if (ip_ver == 6) {
        // Handle IPv6
        if (len < sizeof(ip6_hdr)) return;
        auto *ip = (ip6_hdr *)pkt;

        // Walk extension headers to find TCP or UDP
        uint8_t next = ip->next_hdr;
        int offset = sizeof(ip6_hdr);
        int remaining = ntohs(ip->payload_len);

        while (next != IPPROTO_TCP && next != IPPROTO_UDP && next != 59) {
            if (remaining < 2 || offset + 2 > (int)len) return;
            uint8_t ext_len = pkt[offset + 1];
            switch (next) {
                case 0: case 43: case 44: case 50: case 51: case 60:
                case 135: case 139: case 140:
                    if (ext_len == 0) ext_len = 8;
                    offset += (ext_len + 1) * 8;
                    remaining -= (ext_len + 1) * 8;
                    next = pkt[offset - (ext_len + 1) * 8];
                    break;
                default:
                    offset += 8;
                    remaining -= 8;
                    next = pkt[offset - 8];
                    break;
            }
        }

        if (next == 59) return; // No next header

        if (next == IPPROTO_TCP) {
            if (offset + (int)sizeof(tcp_hdr) > (int)len) return;
            version = 6;
            tcp_raw = (uint8_t *)pkt + offset;
            tcp_raw_len = (int)len - offset;
            memcpy(saddr6, ip->saddr, 16);
            memcpy(daddr6, ip->daddr, 16);
        } else if (next == IPPROTO_UDP) {
            if (offset + (int)sizeof(udp_hdr) > (int)len) return;
            memcpy(saddr6, ip->saddr, 16);
            memcpy(daddr6, ip->daddr, 16);
            handle_udp_packet(pkt, len, tun_fd, 6, saddr6, daddr6, (udp_hdr*)(pkt + offset));
            return;
        } else {
            return;
        }
    } else {
        return; // unknown IP version
    }

    g_stats[0]++;
    g_stats[1] += len;

    auto *tcp = (tcp_hdr *)tcp_raw;
    if (tcp_raw_len < (int)sizeof(tcp_hdr)) return;
    int tcp_hl = tcp_hdrlen(tcp);
    if (tcp_hl < 20 || tcp_hl > tcp_raw_len) return;

    uint16_t flags = tcp_flags_val(tcp);
    uint16_t sport = ntohs(tcp->src_port);
    uint16_t dport = ntohs(tcp->dst_port);

    std::string key;
    if (version == 4)
        key = conn_key4(ntohl(saddr4_n), ntohl(daddr4_n), sport, dport);
    else
        key = conn_key6(saddr6, daddr6, sport, dport);

    if (flags & TF_SYN) {
        if (flags & TF_ACK) return;

        // Build target string for proxy
        char target[64];
        if (version == 4) {
            struct in_addr ia; ia.s_addr = daddr4_n;
            snprintf(target, sizeof(target), "%s:%d", inet_ntoa(ia), dport);
        } else {
            char ip6[INET6_ADDRSTRLEN];
            inet_ntop(AF_INET6, daddr6, ip6, sizeof(ip6));
            snprintf(target, sizeof(target), "[%s]:%d", ip6, dport);
        }

        int pfd = proxy_connect(target, dport);
        if (pfd < 0) {
            g_stats[4]++;
            return;
        }

        auto conn = std::make_unique<Conn>();
        conn->ver = version;
        conn->sport = sport;
        conn->dport = dport;
        conn->proxy_fd = pfd;
        conn->cli_seq = ntohl(tcp->seq);
        conn->cli_ack = ntohl(tcp->ack);
        conn->srv_seq = (rand() % 65536) + 100000;
        conn->srv_ack = conn->cli_seq + 1;
        conn->last_seen = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        conn->fin_recvd = false;
        conn->fin_sent = false;

        if (version == 4) {
            *(uint32_t*)(conn->saddr) = ntohl(saddr4_n);
            *(uint32_t*)(conn->daddr) = ntohl(daddr4_n);
        } else {
            memcpy(conn->saddr, saddr6, 16);
            memcpy(conn->daddr, daddr6, 16);
        }

        {
            std::lock_guard<std::mutex> lk(g_mtx);
            g_conns[key] = std::move(conn);
        }
        g_stats[5]++;

        if (dport == 443 || dport == 8443) g_stats[3]++;
        else g_stats[2]++;

        // Send SYN-ACK
        if (version == 4) {
            uint8_t buf[sizeof(ip4_hdr) + sizeof(tcp_hdr)];
            memset(buf, 0, sizeof(buf));
            auto *rip = (ip4_hdr *)buf;
            auto *rtcp = (tcp_hdr *)(buf + sizeof(ip4_hdr));
            rip->ver_ihl = 0x45;
            rip->total_len = htons(sizeof(buf));
            rip->ttl = 64;
            rip->proto = IPPROTO_TCP;
            rip->saddr = daddr4_n;
            rip->daddr = saddr4_n;
            rtcp->src_port = tcp->dst_port;
            rtcp->dst_port = tcp->src_port;
            rtcp->seq = htonl(conn->srv_seq);
            rtcp->ack = htonl(conn->srv_ack);
            rtcp->doff_flags = htons((5 << 12) | TF_SYN | TF_ACK);
            rtcp->window = htons(65535);
            rip->csum = inet_csum(rip, sizeof(ip4_hdr));
            set_tcp_csum(rtcp, sizeof(tcp_hdr), rip->saddr, rip->daddr);
            write_tun(tun_fd, buf, sizeof(buf));
        } else {
            uint8_t buf[sizeof(ip6_hdr) + sizeof(tcp_hdr)];
            memset(buf, 0, sizeof(buf));
            auto *rip = (ip6_hdr *)buf;
            auto *rtcp = (tcp_hdr *)(buf + sizeof(ip6_hdr));
            rip->vfc_flow = htonl(0x60000000);
            rip->payload_len = htons(sizeof(tcp_hdr));
            rip->next_hdr = IPPROTO_TCP;
            rip->hop_limit = 64;
            memcpy(rip->saddr, daddr6, 16);
            memcpy(rip->daddr, saddr6, 16);
            rtcp->src_port = tcp->dst_port;
            rtcp->dst_port = tcp->src_port;
            rtcp->seq = htonl(conn->srv_seq);
            rtcp->ack = htonl(conn->srv_ack);
            rtcp->doff_flags = htons((5 << 12) | TF_SYN | TF_ACK);
            rtcp->window = htons(65535);
            set_tcp6_csum(rtcp, sizeof(tcp_hdr), rip->saddr, rip->daddr);
            write_tun(tun_fd, buf, sizeof(buf));
        }
        return;
    }

    // Existing connection
    Conn *conn = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        auto it = g_conns.find(key);
        if (it != g_conns.end()) conn = it->second.get();
    }
    if (!conn) return;

    handle_conn_pkt(conn, tcp, tcp_raw_len, flags, version, tun_fd);

    // Remove if both sides done
    if (conn->fin_recvd && conn->proxy_fd < 0) {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_conns.erase(key);
        g_stats[5]--;
    }
}

static void pkt_loop(int fd) {
    LOGI("pkt_loop started fd=%d", fd);
    uint8_t buf[TUN_MTU * 2];
    auto last_clean = std::chrono::steady_clock::now();

    while (g_run.load()) {
        struct pollfd pf = {fd, POLLIN, 0};
        int r = poll(&pf, 1, 100);
        if (r < 0) { if (errno == EINTR) continue; break; }

        if (pf.revents & POLLIN) {
            ssize_t n = read(fd, buf, sizeof(buf));
            if (n > 0)
                process_packet(buf, (uint16_t)n, fd);
            else if (n < 0 && errno != EAGAIN)
                break;
        }

        // Poll proxy fds for async responses + cleanup
        std::vector<std::string> to_remove;
        {
            std::lock_guard<std::mutex> lk(g_mtx);
            auto now = std::chrono::steady_clock::now();
            auto now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                now.time_since_epoch()).count();

            for (auto &p : g_conns) {
                Conn *c = p.second.get();

                if (now_ms - c->last_seen > IDLE_TIMEOUT_MS) {
                    if (c->proxy_fd >= 0) close(c->proxy_fd);
                    to_remove.push_back(p.first);
                    continue;
                }

                if (c->proxy_fd < 0 || c->fin_sent) continue;

                struct pollfd pfd = {c->proxy_fd, POLLIN, 0};
                if (poll(&pfd, 1, 0) > 0 && (pfd.revents & POLLIN)) {
                    uint8_t rbuf[BUF_SIZE];
                    int n = (int)read(c->proxy_fd, rbuf, sizeof(rbuf));
                    if (n > 0) {
                        // Build response packet
                        if (c->ver == 4) {
                            uint32_t saddr_n = htonl(*(uint32_t*)c->daddr);
                            uint32_t daddr_n = htonl(*(uint32_t*)c->saddr);
                            uint16_t tcp_len = sizeof(tcp_hdr) + n;
                            uint16_t total = sizeof(ip4_hdr) + tcp_len;
                            auto *buf2 = (uint8_t *)malloc(total);
                            if (!buf2) continue;
                            auto *rip = (ip4_hdr *)buf2;
                            auto *rtcp = (tcp_hdr *)(buf2 + sizeof(ip4_hdr));
                            memset(rip, 0, sizeof(ip4_hdr));
                            rip->ver_ihl = 0x45;
                            rip->total_len = htons(total);
                            rip->ttl = 64; rip->proto = IPPROTO_TCP;
                            rip->saddr = saddr_n; rip->daddr = daddr_n;
                            rtcp->src_port = htons(c->dport);
                            rtcp->dst_port = htons(c->sport);
                            rtcp->seq = htonl(c->srv_seq);
                            rtcp->ack = htonl(c->srv_ack);
                            rtcp->doff_flags = htons((5 << 12) | TF_PSH | TF_ACK);
                            rtcp->window = htons(65535);
                            memcpy(buf2 + sizeof(ip4_hdr) + sizeof(tcp_hdr), rbuf, n);
                            rip->csum = inet_csum(rip, sizeof(ip4_hdr));
                            set_tcp_csum(rtcp, tcp_len, rip->saddr, rip->daddr);
                            write_tun(fd, buf2, total);
                            free(buf2);
                        } else {
                            uint16_t tcp_len = sizeof(tcp_hdr) + n;
                            uint16_t total = sizeof(ip6_hdr) + tcp_len;
                            auto *buf2 = (uint8_t *)malloc(total);
                            if (!buf2) continue;
                            auto *rip = (ip6_hdr *)buf2;
                            auto *rtcp = (tcp_hdr *)(buf2 + sizeof(ip6_hdr));
                            memset(rip, 0, sizeof(ip6_hdr));
                            rip->vfc_flow = htonl(0x60000000);
                            rip->payload_len = htons(tcp_len);
                            rip->next_hdr = IPPROTO_TCP;
                            rip->hop_limit = 64;
                            memcpy(rip->saddr, c->daddr, 16);
                            memcpy(rip->daddr, c->saddr, 16);
                            rtcp->src_port = htons(c->dport);
                            rtcp->dst_port = htons(c->sport);
                            rtcp->seq = htonl(c->srv_seq);
                            rtcp->ack = htonl(c->srv_ack);
                            rtcp->doff_flags = htons((5 << 12) | TF_PSH | TF_ACK);
                            rtcp->window = htons(65535);
                            memcpy(buf2 + sizeof(ip6_hdr) + sizeof(tcp_hdr), rbuf, n);
                            set_tcp6_csum(rtcp, tcp_len, rip->saddr, rip->daddr);
                            write_tun(fd, buf2, total);
                            free(buf2);
                        }
                        c->srv_seq += n;
                    } else if (n == 0) {
                        close(c->proxy_fd);
                        c->proxy_fd = -1;
                        if (c->fin_recvd)
                            to_remove.push_back(p.first);
                    }
                }
            }

            for (auto &k : to_remove) {
                g_conns.erase(k);
                g_stats[5]--;
            }
        }

        // Poll UDP sockets for incoming responses
        {
            std::lock_guard<std::mutex> lk(g_udp_mtx);
            if (!g_udp_conns.empty()) {
                // Build pollfd array for UDP sockets
                int idx = 0;
                std::vector<struct pollfd> udp_pfds;
                std::vector<UdpConn*> udp_ptrs;
                for (auto &p : g_udp_conns) {
                    if (idx >= UDP_POLL_MAX) break;
                    udp_pfds.push_back({p.second->udp_fd, POLLIN, 0});
                    udp_ptrs.push_back(p.second.get());
                    idx++;
                }
                if (!udp_pfds.empty()) {
                    int r = poll(udp_pfds.data(), udp_pfds.size(), 0);
                    if (r > 0) {
                        for (size_t i = 0; i < udp_pfds.size(); i++) {
                            if (udp_pfds[i].revents & POLLIN) {
                                UdpConn *uc = udp_ptrs[i];
                                uint8_t rbuf[1500];
                                socklen_t from_len = sizeof(struct sockaddr_storage);
                                struct sockaddr_storage from;
                                int n = recvfrom(uc->udp_fd, rbuf, sizeof(rbuf), 0,
                                                  (struct sockaddr *)&from, &from_len);
                                if (n > 0) {
                                    // Write response back to TUN
                                    // Swap src/dst: response comes from real server to client
                                    write_udp_response(fd, (uint8_t*)rbuf, (uint16_t)n,
                                                        uc->daddr, uc->saddr,
                                                        uc->dport, uc->sport,
                                                        uc->ver);
                                } else if (n < 0 && errno != EAGAIN) {
                                    // Socket error, mark for cleanup
                                    uc->last_seen = 0;
                                }
                            }
                        }
                    }
                }
            }
        }

        // Periodic cleanup
        auto now = std::chrono::steady_clock::now();
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now - last_clean).count();
        if (ms >= CLEANUP_MS) {
            auto now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                now.time_since_epoch()).count();

            // Cleanup TCP connections
            {
                std::lock_guard<std::mutex> lk(g_mtx);
                for (auto it = g_conns.begin(); it != g_conns.end(); ) {
                    if (now_ms - it->second->last_seen > IDLE_TIMEOUT_MS) {
                        if (it->second->proxy_fd >= 0) close(it->second->proxy_fd);
                        it = g_conns.erase(it);
                        g_stats[5]--;
                    } else ++it;
                }
            }

            // Cleanup UDP connections
            {
                std::lock_guard<std::mutex> lk(g_udp_mtx);
                for (auto it = g_udp_conns.begin(); it != g_udp_conns.end(); ) {
                    if (now_ms - it->second->last_seen > UDP_IDLE_TIMEOUT_MS ||
                        it->second->last_seen == 0) {
                        close(it->second->udp_fd);
                        it = g_udp_conns.erase(it);
                    } else ++it;
                }
            }

            last_clean = now;
        }
    }

    // Cleanup all on exit
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        for (auto &p : g_conns)
            if (p.second->proxy_fd >= 0) close(p.second->proxy_fd);
        g_conns.clear();
        g_stats[5] = 0;
    }
    {
        std::lock_guard<std::mutex> lk(g_udp_mtx);
        for (auto &p : g_udp_conns) close(p.second->udp_fd);
        g_udp_conns.clear();
    }
    LOGI("pkt_loop ended");
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_tunnel_demo_tunneldemo_native_TunEngineBridge_init(
    JNIEnv *env, jclass, jint tun_fd)
{
    if (g_run.load()) return 0;
    if (tun_fd < 0) return -1;

    // Cache JavaVM for later JNI calls from native threads
    env->GetJavaVM(&g_jvm);

    g_tun_fd = tun_fd;
    g_run = true;
    for (auto &s : g_stats) s = 0;
    std::thread(pkt_loop, tun_fd).detach();
    LOGI("Engine started fd=%d", tun_fd);
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_tunnel_demo_tunneldemo_native_TunEngineBridge_nativeIsRunning(
    JNIEnv *env, jclass)
{
    return g_run.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_tunnel_demo_tunneldemo_native_TunEngineBridge_stop(
    JNIEnv *env, jclass)
{
    g_run = false;
    int fd = g_tun_fd.exchange(-1);
    if (fd >= 0) { uint8_t c = 0; write(fd, &c, 1); }
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        for (auto &p : g_conns)
            if (p.second->proxy_fd >= 0) close(p.second->proxy_fd);
        g_conns.clear();
        g_stats[5] = 0;
    }
    {
        std::lock_guard<std::mutex> lk(g_udp_mtx);
        for (auto &p : g_udp_conns) close(p.second->udp_fd);
        g_udp_conns.clear();
    }
    LOGI("Engine stopped");
}

JNIEXPORT void JNICALL
Java_com_tunnel_demo_tunneldemo_native_TunEngineBridge_setProxyPort(
    JNIEnv *env, jclass, jint port)
{
    g_proxy_port = port;
}

JNIEXPORT jint JNICALL
Java_com_tunnel_demo_tunneldemo_native_TunEngineBridge_getStats(
    JNIEnv *env, jclass, jint type)
{
    if (type < 0 || type > 5) return 0;
    return (jint)g_stats[type].load();
}

JNIEXPORT jboolean JNICALL
Java_com_tunnel_demo_tunneldemo_native_TunEngineBridge_nativeProtectSocket(
    JNIEnv *env, jclass, jint fd)
{
    // Forward to the Kotlin bridge that calls InspectionVpnService.protectSocket(fd)
    jclass cls = env->FindClass("com/tunnel/demo/tunneldemo/native/TunEngineBridge");
    if (!cls) return JNI_FALSE;
    jmethodID mid = env->GetStaticMethodID(cls, "nativeProtectSocket", "(I)Z");
    if (!mid) { env->DeleteLocalRef(cls); return JNI_FALSE; }
    jboolean result = env->CallStaticBooleanMethod(cls, mid, fd);
    env->DeleteLocalRef(cls);
    return result;
}

} // extern "C"
