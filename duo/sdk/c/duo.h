/*
 * duo.h: the GameNative Duo SDK for native mods (C/C++, any DirectX/OpenGL game via Ultimate ASI Loader). MIT.
 * Header-only: in exactly one file, #define DUO_IMPLEMENTATION before #include "duo.h".
 * Same ideas as GameNativeDuo.Sdk (C#) and duo.lua; wire format: duo/docs/engine-adapter-contract.md.
 *
 *   duo_start("native-mygame");
 *   duo_on_touch(my_touch, NULL);
 *   every frame (or 30 times a second from your own thread): duo_tick(window_w, window_h);
 *   duo_publish("mygame.hp", "42");          // companion state, sent only while a card shows it
 *
 * Not thread-safe: call everything from one thread. No allocations after duo_start.
 */
#ifndef DUO_H
#define DUO_H

#ifdef __cplusplus
extern "C" {
#endif

#define DUO_SDK_VERSION "1.1"

enum { DUO_DOWN = 0, DUO_MOVE = 1, DUO_UP = 2, DUO_CANCEL = 3 };
enum { DUO_OK = 0, DUO_NOT_NOW = 1, DUO_UNKNOWN = 2, DUO_FAILED = 3 };
enum { DUO_DEBUG = 0, DUO_INFO = 1, DUO_WARN = 2, DUO_ERROR = 3 };
enum {
    DUO_CAP_TOUCH = 1, DUO_CAP_UI_RULES = 2, DUO_CAP_ELEMENTS = 4, DUO_CAP_BG_BASIC = 8, DUO_CAP_BG_BLUR = 16,
    DUO_CAP_BG_GAME = 32, DUO_CAP_STATE = 64, DUO_CAP_COMMANDS = 128, DUO_CAP_ELEMENTS_CHANGED = 256
};

typedef struct { int x, y, w, h; } duo_rect;

typedef struct {
    int valid;
    int screen_w, screen_h;
    duo_rect top, bottom;
    int gap;
    float bottom_scale;
    int bottom_shift;
    int port;
    char token[48]; /* the session token the app expects in every HELLO; "" from an app that has none */
} duo_layout;

typedef void (*duo_touch_fn)(int action, int pointer, float x, float y, void *user);
/* A command: json is {"cmd": "...", "args": {...}}; answer with duo_command_result(request, ...). */
typedef void (*duo_command_fn)(unsigned request, const char *json, void *user);
/* The editor wants elements.json: write it with duo_write_file("elements.json", ...), then duo_elements_written(request). */
typedef void (*duo_dump_fn)(unsigned request, void *user);
/* ui.json changed (the user's UI layout): read it with duo_read_file("ui.json", ...). */
typedef void (*duo_rules_fn)(void *user);

/* Opens the bridge. engine: a short id like "native-mygame" (the SDK version is appended). 0 on failure. */
int duo_start(const char *engine);
void duo_stop(void);
/* Call once per frame (or ~30 Hz) with the game window's size. */
void duo_tick(int window_w, int window_h);

const duo_layout *duo_get_layout(void);
/* The window is the tall window the layout was made for. */
int duo_is_split(void);
/* Extra capabilities the mod implements itself (backgrounds). */
void duo_set_extra_caps(unsigned caps);

void duo_on_touch(duo_touch_fn fn, void *user);
void duo_on_command(duo_command_fn fn, void *user);
void duo_on_dump(duo_dump_fn fn, void *user);
void duo_on_rules(duo_rules_fn fn, void *user);

void duo_command_result(unsigned request, int status, const char *message);
void duo_elements_written(unsigned request);
void duo_elements_changed(void);

/* Companion state. value_json is a JSON value ("42", "\"Crossroads\"", "[1,2]"); NULL deletes the key. */
void duo_publish(const char *key, const char *value_json);
/* True when a visible card reads key: compute costly values only then. */
int duo_wants(const char *key);

void duo_log(int level, const char *text);
void duo_haptic(int ms, int strength);

/* Files in %AppData%\GameNativeDuo\ (elements.json, features.json, ui.json). Returns bytes read / 1 on success. */
int duo_read_file(const char *name, char *buf, int size);
int duo_write_file(const char *name, const char *text);
/* Overrides the folder (tests, the simulator); must end with a separator. */
void duo_set_dir(const char *dir);

/* Pure helpers (also used by the tests). */
int duo_parse_layout(const char *json, duo_layout *out);
int duo_matches(const char *subscription, const char *key);

#ifdef __cplusplus
}
#endif
#endif /* DUO_H */

/* ================================================================================================================ */
#ifdef DUO_IMPLEMENTATION

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#ifdef _MSC_VER
#pragma comment(lib, "ws2_32.lib")
#endif
typedef SOCKET duo__socket;
#define DUO__BAD INVALID_SOCKET
#define duo__close closesocket
#else
#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
typedef int duo__socket;
#define DUO__BAD (-1)
#define duo__close close
#endif

#define DUO__MAX_STATE 8192
#define DUO__MAX_KEYS 256
#define DUO__MAX_SUBS 64

static duo__socket duo__sock = DUO__BAD;

static struct {
    char engine[64];
    char dir[512];
    duo_layout layout;
    int window_w, window_h;
    unsigned extra_caps;
    double next_hello, next_files;
    long layout_stamp, ui_stamp;
    duo_touch_fn touch; void *touch_user;
    duo_command_fn command; void *command_user;
    duo_dump_fn dump; void *dump_user;
    duo_rules_fn rules; void *rules_user;
    int published;
    char subs[DUO__MAX_SUBS][96];
    int sub_count, snapshot_due;
    char keys[DUO__MAX_KEYS][96];
    char *values[DUO__MAX_KEYS];
    unsigned char dirty[DUO__MAX_KEYS];
    int key_count;
    double last_state;
} duo__s;

static double duo__now(void) {
#ifdef _WIN32
    return (double)GetTickCount64() / 1000.0;
#else
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
#endif
}

static void duo__u32(unsigned char *p, unsigned v) {
    p[0] = (unsigned char)v; p[1] = (unsigned char)(v >> 8); p[2] = (unsigned char)(v >> 16); p[3] = (unsigned char)(v >> 24);
}

static unsigned duo__get_u32(const unsigned char *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | ((unsigned)p[3] << 24); }

static void duo__send(const unsigned char *p, int n) {
    struct sockaddr_in to;
    if (duo__sock == DUO__BAD || duo__s.layout.port <= 0) return;
    memset(&to, 0, sizeof to);
    to.sin_family = AF_INET;
    to.sin_port = htons((unsigned short)duo__s.layout.port);
    to.sin_addr.s_addr = htonl(0x7F000001);
    sendto(duo__sock, (const char *)p, n, 0, (struct sockaddr *)&to, sizeof to);
}

static void duo__send_type(unsigned char type, const void *body, int n) {
    unsigned char p[DUO__MAX_STATE + 16];
    if (n > (int)sizeof p - 4) return;
    p[0] = 'G'; p[1] = 'D'; p[2] = 1; p[3] = type;
    if (n > 0) memcpy(p + 4, body, (size_t)n);
    duo__send(p, 4 + n);
}

void duo_set_dir(const char *dir) { snprintf(duo__s.dir, sizeof duo__s.dir, "%s", dir); }

static void duo__path(char *out, int size, const char *name) { snprintf(out, (size_t)size, "%s%s", duo__s.dir, name); }

int duo_read_file(const char *name, char *buf, int size) {
    char path[600];
    FILE *f;
    int n;
    duo__path(path, sizeof path, name);
    f = fopen(path, "rb");
    if (!f) return 0;
    n = (int)fread(buf, 1, (size_t)(size - 1), f);
    fclose(f);
    buf[n < 0 ? 0 : n] = 0;
    return n;
}

int duo_write_file(const char *name, const char *text) {
    char path[600], tmp[610];
    FILE *f;
    duo__path(path, sizeof path, name);
    snprintf(tmp, sizeof tmp, "%s.tmp", path);
    f = fopen(tmp, "wb");
    if (!f) return 0;
    fputs(text, f);
    fclose(f);
    /* Replace in one step: between a remove and a rename, a reader would find no file at all. */
#ifdef _WIN32
    return MoveFileExA(tmp, path, MOVEFILE_REPLACE_EXISTING) != 0;
#else
    return rename(tmp, path) == 0;
#endif
}

/* The number after "key": inside the object that follows "obj": (or anywhere when obj is NULL). */
static double duo__num(const char *json, const char *obj, const char *key, double fallback) {
    char pat[64];
    const char *p = json, *end = NULL;
    if (obj) {
        snprintf(pat, sizeof pat, "\"%s\"", obj);
        p = strstr(json, pat);
        if (!p) return fallback;
        p = strchr(p + strlen(pat), '{');
        if (!p) return fallback;
        end = strchr(p, '}');
    }
    snprintf(pat, sizeof pat, "\"%s\"", key);
    p = strstr(p, pat);
    if (!p || (end && p > end)) return fallback;
    p = strchr(p + strlen(pat), ':');
    if (!p) return fallback;
    return strtod(p + 1, NULL);
}

/* The string after "key": inside the object that follows "obj": (or anywhere when obj is NULL); "" when absent. */
static void duo__str(const char *json, const char *obj, const char *key, char *out, size_t size) {
    char pat[64];
    const char *p = json, *end = NULL;
    size_t n = 0;
    out[0] = 0;
    if (obj) {
        snprintf(pat, sizeof pat, "\"%s\"", obj);
        p = strstr(json, pat);
        if (!p) return;
        p = strchr(p + strlen(pat), '{');
        if (!p) return;
        end = strchr(p, '}');
    }
    snprintf(pat, sizeof pat, "\"%s\"", key);
    p = strstr(p, pat);
    if (!p || (end && p > end)) return;
    p = strchr(p + strlen(pat), ':');
    if (!p) return;
    p++;
    while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
    if (*p != '"') return;
    for (p++; *p && *p != '"' && n + 1 < size; p++) out[n++] = *p;
    out[n] = 0;
}

static void duo__rect(const char *json, const char *obj, duo_rect *r) {
    r->x = (int)duo__num(json, obj, "x", 0); r->y = (int)duo__num(json, obj, "y", 0);
    r->w = (int)duo__num(json, obj, "w", 0); r->h = (int)duo__num(json, obj, "h", 0);
}

int duo_parse_layout(const char *json, duo_layout *out) {
    memset(out, 0, sizeof *out);
    out->screen_w = (int)duo__num(json, "screen", "w", 0);
    out->screen_h = (int)duo__num(json, "screen", "h", 0);
    if (out->screen_w <= 0 || out->screen_h <= 0) return 0;
    duo__rect(json, "top", &out->top);
    duo__rect(json, "bottom", &out->bottom);
    out->gap = (int)duo__num(json, NULL, "gap", 0);
    out->bottom_scale = (float)duo__num(json, NULL, "bottomScale", 1);
    out->bottom_shift = (int)duo__num(json, NULL, "bottomShift", 0);
    out->port = (int)duo__num(json, "bridge", "port", 0);
    duo__str(json, "bridge", "token", out->token, sizeof out->token);
    out->valid = 1;
    return 1;
}

int duo_matches(const char *s, const char *key) {
    size_t ls = strlen(s), lk = strlen(key);
    if (strcmp(s, key) == 0) return 1;
    if (lk <= ls || strncmp(key, s, ls) != 0) return 0;
    return key[ls] == '.' || (ls > 0 && s[ls - 1] == '.');
}

int duo_wants(const char *key) {
    int i;
    for (i = 0; i < duo__s.sub_count; i++)
        if (duo_matches(duo__s.subs[i], key)) return 1;
    return 0;
}

static long duo__stamp(const char *name) {
    /* Cheap change check without stat portability trouble: size and a sample of the content. */
    char buf[4096];
    int n = duo_read_file(name, buf, sizeof buf), i;
    long h = n;
    for (i = 0; i < n; i++) h = h * 31 + (unsigned char)buf[i];
    return h;
}

static void duo__read_files(void) {
    char buf[4096];
    long s = duo__stamp("layout.json");
    if (s != duo__s.layout_stamp) {
        duo__s.layout_stamp = s;
        if (duo_read_file("layout.json", buf, sizeof buf) <= 0 || !duo_parse_layout(buf, &duo__s.layout))
            memset(&duo__s.layout, 0, sizeof duo__s.layout);
    }
    if (!duo__s.layout.valid || duo__s.layout.port == 0) {
        int old = duo__s.layout.port;
        char old_token[sizeof duo__s.layout.token];
        memcpy(old_token, duo__s.layout.token, sizeof old_token);
        duo__s.layout.token[0] = 0;
        if (duo_read_file("bridge.json", buf, sizeof buf) > 0) {
            duo__s.layout.port = (int)duo__num(buf, NULL, "port", 0);
            duo__str(buf, NULL, "token", duo__s.layout.token, sizeof duo__s.layout.token);
        } else {
            duo__s.layout.port = 0;
        }
        if (duo__s.layout.port != old || strcmp(old_token, duo__s.layout.token) != 0) duo__s.next_hello = 0;
    }
    s = duo__stamp("ui.json");
    if (s != duo__s.ui_stamp) {
        duo__s.ui_stamp = s;
        if (duo__s.rules) duo__s.rules(duo__s.rules_user);
    }
}

int duo_start(const char *engine) {
    struct sockaddr_in a;
#ifdef _WIN32
    WSADATA wsa;
    u_long nb = 1;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return 0;
#endif
    if (duo__sock != DUO__BAD) return 1;
    snprintf(duo__s.engine, sizeof duo__s.engine, "%s", engine);
    if (!duo__s.dir[0]) {
        const char *appdata = getenv("APPDATA");
        if (appdata) snprintf(duo__s.dir, sizeof duo__s.dir, "%s\\GameNativeDuo\\", appdata);
    }
    duo__sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (duo__sock == DUO__BAD) return 0;
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(0x7F000001);
    if (bind(duo__sock, (struct sockaddr *)&a, sizeof a) != 0) {
        duo__close(duo__sock);
        duo__sock = DUO__BAD;
        return 0;
    }
#ifdef _WIN32
    ioctlsocket(duo__sock, FIONBIO, &nb);
#else
    fcntl(duo__sock, F_SETFL, fcntl(duo__sock, F_GETFL, 0) | O_NONBLOCK);
#endif
    duo__s.layout_stamp = duo__s.ui_stamp = -1;
    duo__read_files();
    return 1;
}

void duo_stop(void) {
    int i;
    if (duo__sock != DUO__BAD) duo__close(duo__sock);
    duo__sock = DUO__BAD;
    for (i = 0; i < duo__s.key_count; i++) free(duo__s.values[i]);
    duo__s.key_count = 0;
}

const duo_layout *duo_get_layout(void) { return &duo__s.layout; }

int duo_is_split(void) {
    const duo_layout *l = &duo__s.layout;
    return l->valid && abs(duo__s.window_w - l->screen_w) <= 2 && abs(duo__s.window_h - l->screen_h) <= 2;
}

void duo_set_extra_caps(unsigned caps) { duo__s.extra_caps = caps; }
void duo_on_touch(duo_touch_fn fn, void *user) { duo__s.touch = fn; duo__s.touch_user = user; }
void duo_on_command(duo_command_fn fn, void *user) { duo__s.command = fn; duo__s.command_user = user; }
void duo_on_dump(duo_dump_fn fn, void *user) { duo__s.dump = fn; duo__s.dump_user = user; }
void duo_on_rules(duo_rules_fn fn, void *user) { duo__s.rules = fn; duo__s.rules_user = user; }

void duo_command_result(unsigned request, int status, const char *message) {
    unsigned char b[512];
    int n = message ? (int)strlen(message) : 0;
    if (n > 500) n = 500;
    duo__u32(b, request);
    b[4] = (unsigned char)status;
    if (n) memcpy(b + 5, message, (size_t)n);
    duo__send_type(0x85, b, 5 + n);
}

void duo_elements_written(unsigned request) {
    unsigned char b[4];
    duo__u32(b, request);
    duo__send_type(0x83, b, 4);
}

void duo_elements_changed(void) { duo__send_type(0x86, NULL, 0); }

void duo_log(int level, const char *text) {
    unsigned char b[512];
    int n = (int)strlen(text);
    if (n > 500) n = 500;
    b[0] = (unsigned char)level;
    memcpy(b + 1, text, (size_t)n);
    duo__send_type(0x87, b, 1 + n);
}

void duo_haptic(int ms, int strength) {
    unsigned char b[3];
    if (ms < 0) ms = 0;
    if (ms > 65535) ms = 65535;
    b[0] = (unsigned char)ms; b[1] = (unsigned char)(ms >> 8);
    b[2] = (unsigned char)(strength < 1 ? 1 : strength > 255 ? 255 : strength);
    duo__send_type(0x88, b, 3);
}

void duo_publish(const char *key, const char *value_json) {
    int i;
    duo__s.published = 1;
    for (i = 0; i < duo__s.key_count; i++)
        if (strcmp(duo__s.keys[i], key) == 0) break;
    if (i == duo__s.key_count) {
        if (i >= DUO__MAX_KEYS || strlen(key) >= sizeof duo__s.keys[0]) return;
        snprintf(duo__s.keys[i], sizeof duo__s.keys[i], "%s", key);
        duo__s.values[i] = NULL;
        duo__s.key_count++;
    } else if ((duo__s.values[i] == NULL && value_json == NULL) ||
               (duo__s.values[i] && value_json && strcmp(duo__s.values[i], value_json) == 0)) {
        return;
    }
    free(duo__s.values[i]);
    duo__s.values[i] = value_json ? strdup(value_json) : NULL;
    duo__s.dirty[i] = 1;
}

static void duo__flush_state(double now) {
    char body[DUO__MAX_STATE + 8];
    int i, n = 4, any = 0, full = duo__s.snapshot_due, first = 1;
    if (duo__s.sub_count == 0) {
        memset(duo__s.dirty, 0, sizeof duo__s.dirty);
        return;
    }
    if (now - duo__s.last_state < 0.033) return;
    for (i = 0; i < duo__s.key_count && !any; i++) any = full || duo__s.dirty[i];
    if (!any) return;
    duo__s.last_state = now;
    body[0] = (char)(full ? 1 : 0); body[1] = body[2] = body[3] = 0;
    body[n++] = '{';
    for (i = 0; i < duo__s.key_count; i++) {
        const char *v = duo__s.values[i] ? duo__s.values[i] : "null";
        int need;
        if (!(full ? duo__s.values[i] != NULL : duo__s.dirty[i]) || !duo_wants(duo__s.keys[i])) continue;
        duo__s.dirty[i] = 0;
        need = (int)(strlen(duo__s.keys[i]) + strlen(v) + 4);
        if (need + 6 > DUO__MAX_STATE) continue; /* one value bigger than a datagram: split it into keys */
        if (n + need + 2 > DUO__MAX_STATE + 4) { /* send this chunk, start another */
            body[n++] = '}';
            duo__send_type(0x84, body, n);
            body[0] = 0; n = 4; body[n++] = '{'; first = 1;
        }
        n += snprintf(body + n, sizeof body - (size_t)n, "%s\"%s\":%s", first ? "" : ",", duo__s.keys[i], v);
        first = 0;
    }
    memset(duo__s.dirty, 0, sizeof duo__s.dirty);
    duo__s.snapshot_due = 0;
    body[n++] = '}';
    duo__send_type(0x84, body, n);
}

static void duo__subscribe(const char *json) {
    const char *p = strchr(json, '[');
    duo__s.sub_count = 0;
    while (p && duo__s.sub_count < DUO__MAX_SUBS) {
        const char *a = strchr(p, '"'), *b;
        if (!a) break;
        b = strchr(a + 1, '"');
        if (!b) break;
        if (b - a - 1 < (int)sizeof duo__s.subs[0]) {
            memcpy(duo__s.subs[duo__s.sub_count], a + 1, (size_t)(b - a - 1));
            duo__s.subs[duo__s.sub_count][b - a - 1] = 0;
            duo__s.sub_count++;
        }
        p = b + 1;
    }
    duo__s.snapshot_due = duo__s.sub_count > 0;
}

static void duo__receive(void) {
    unsigned char b[2048];
    int k;
    for (k = 0; k < 64; k++) {
        struct sockaddr_in from;
#ifdef _WIN32
        int fl = sizeof from;
#else
        socklen_t fl = sizeof from;
#endif
        int n = (int)recvfrom(duo__sock, (char *)b, sizeof b - 1, 0, (struct sockaddr *)&from, &fl);
        if (n <= 0) return;
        b[n] = 0;
        if (ntohl(from.sin_addr.s_addr) != 0x7F000001 || ntohs(from.sin_port) != duo__s.layout.port) continue;
        if (n < 4 || b[0] != 'G' || b[1] != 'D' || b[2] != 1) continue;
        switch (b[3]) {
        case 0x01:
            if (n >= 16 && duo__s.touch) {
                float x, y;
                memcpy(&x, b + 8, 4); memcpy(&y, b + 12, 4);
                duo__s.touch(b[4], b[5], x, y, duo__s.touch_user);
            }
            break;
        case 0x02:
            duo__s.ui_stamp = -1; /* re-read now */
            break;
        case 0x03:
            if (n >= 8) {
                if (duo__s.dump) duo__s.dump(duo__get_u32(b + 4), duo__s.dump_user);
                else duo_elements_written(duo__get_u32(b + 4));
            }
            break;
        case 0x04:
            duo__subscribe((const char *)b + 4);
            break;
        case 0x05:
            if (n >= 8) {
                if (duo__s.command) duo__s.command(duo__get_u32(b + 4), (const char *)b + 8, duo__s.command_user);
                else duo_command_result(duo__get_u32(b + 4), DUO_UNKNOWN, "no commands");
            }
            break;
        }
    }
}

void duo_tick(int window_w, int window_h) {
    double now;
    if (duo__sock == DUO__BAD) return;
    duo__s.window_w = window_w;
    duo__s.window_h = window_h;
    now = duo__now();
    if (now >= duo__s.next_files || duo__s.ui_stamp == -1) {
        duo__s.next_files = now + 0.5;
        duo__read_files();
    }
    if (now >= duo__s.next_hello && duo__s.layout.port > 0) {
        unsigned char b[192];
        unsigned caps = DUO_CAP_UI_RULES | DUO_CAP_ELEMENTS | DUO_CAP_ELEMENTS_CHANGED | duo__s.extra_caps;
        int n;
        if (duo__s.touch) caps |= DUO_CAP_TOUCH;
        if (duo__s.published) caps |= DUO_CAP_STATE;
        if (duo__s.command) caps |= DUO_CAP_COMMANDS;
        duo__s.next_hello = now + 2;
        duo__u32(b, caps);
        n = snprintf((char *)b + 4, sizeof b - 4, "%s/sdk%s", duo__s.engine, DUO_SDK_VERSION);
        if (n > (int)sizeof b - 5) n = (int)sizeof b - 5;
        /* After a NUL, the session token: proof to the app that this is the game and not another app on the device. */
        if (duo__s.layout.token[0] && 4 + n + 1 + strlen(duo__s.layout.token) <= sizeof b) {
            size_t t = strlen(duo__s.layout.token);
            b[4 + n] = 0;
            memcpy(b + 4 + n + 1, duo__s.layout.token, t);
            n += 1 + (int)t;
        }
        duo__send_type(0x81, b, 4 + n);
    }
    duo__receive();
    duo__flush_state(now);
}

#endif /* DUO_IMPLEMENTATION */
