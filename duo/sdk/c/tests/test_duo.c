/* gcc -std=c99 -D_POSIX_C_SOURCE=200809L -Wall -o /tmp/t duo/sdk/c/tests/test_duo.c && /tmp/t
 * duo.h against a fake app on loopback (POSIX build of the same code the ASI uses on Windows). */
#define DUO_IMPLEMENTATION
#include "../duo.h"
#include <assert.h>
#include <sys/stat.h>

static int touches;
static float last_y;
static void on_touch(int action, int pointer, float x, float y, void *u) { (void)action; (void)pointer; (void)x; (void)u; touches++; last_y = y; }
static void on_command(unsigned request, const char *json, void *u) { (void)u; duo_command_result(request, strstr(json, "ping") ? DUO_OK : DUO_UNKNOWN, "pong"); }

static int app;
static struct sockaddr_in mod;

static int receive(unsigned char type, unsigned char *buf, int size) {
    int i;
    for (i = 0; i < 300; i++) {
        socklen_t fl = sizeof mod;
        int n;
        duo_tick(1920, 2160);
        n = (int)recvfrom(app, buf, (size_t)size, MSG_DONTWAIT, (struct sockaddr *)&mod, &fl);
        if (n > 0 && buf[3] == type) return n;
        usleep(10000);
    }
    return -1;
}

static void send_app(unsigned char type, const void *body, int n) {
    unsigned char p[2048] = { 'G', 'D', 1, type };
    memcpy(p + 4, body, (size_t)n);
    sendto(app, p, (size_t)(4 + n), 0, (struct sockaddr *)&mod, sizeof mod);
}

int main(void) {
    duo_layout l;
    struct sockaddr_in a;
    socklen_t al = sizeof a;
    unsigned char buf[9000];
    char layout[512], dir[] = "/tmp/duo-c-test-XXXXXX", dirs[64];
    int n;

    assert(duo_parse_layout("{\"screen\":{\"w\":1920,\"h\":2160},\"top\":{\"x\":0,\"y\":0,\"w\":1920,\"h\":1080},"
                            "\"bottom\":{\"x\":340,\"y\":1080,\"w\":1240,\"h\":1080},\"gap\":249,\"bottomScale\":1.142,\"bridge\":{\"port\":4000}}", &l));
    assert(l.bottom.x == 340 && l.bottom.h == 1080 && l.gap == 249 && l.port == 4000 && l.bottom_scale > 1.14f);
    assert(l.token[0] == 0); /* an older app: no token */
    assert(duo_matches("hk.charms", "hk.charms.0") && duo_matches("hk.map.", "hk.map.zone") && !duo_matches("hk.charm", "hk.charms"));

    app = socket(AF_INET, SOCK_DGRAM, 0);
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(0x7F000001);
    bind(app, (struct sockaddr *)&a, sizeof a);
    getsockname(app, (struct sockaddr *)&a, &al);
    assert(mkdtemp(dir));
    snprintf(dirs, sizeof dirs, "%s/", dir);
    duo_set_dir(dirs);
    snprintf(layout, sizeof layout, "{\"screen\":{\"w\":1920,\"h\":2160},\"top\":{\"x\":0,\"y\":0,\"w\":1920,\"h\":1080},"
             "\"bottom\":{\"x\":340,\"y\":1080,\"w\":1240,\"h\":1080},\"bridge\":{\"port\":%d,\"token\":\"00112233445566778899aabbccddeeff\"}}", ntohs(a.sin_port));
    assert(duo_write_file("layout.json", layout));

    assert(duo_start("native-test"));
    duo_on_touch(on_touch, NULL);
    duo_on_command(on_command, NULL);
    duo_publish("t.hp", "5");
    duo_publish("t.zone", "\"Crossroads\"");

    n = receive(0x81, buf, sizeof buf);
    /* The engine string, then a NUL and the session token from layout.json. */
    {
        const char expect[] = "native-test/sdk" DUO_SDK_VERSION "\0" "00112233445566778899aabbccddeeff";
        assert(n - 8 == (int)sizeof expect - 1 && memcmp(buf + 8, expect, sizeof expect - 1) == 0);
    }
    assert(duo__get_u32(buf + 4) & DUO_CAP_STATE);
    assert(duo_is_split());

    {
        unsigned char t[12] = { DUO_DOWN, 0, 0, 0 };
        float x = 10, y = 1500;
        memcpy(t + 4, &x, 4); memcpy(t + 8, &y, 4);
        send_app(0x01, t, 12);
        for (n = 0; n < 50 && !touches; n++) { duo_tick(1920, 2160); usleep(10000); }
        assert(touches == 1 && last_y == 1500);
    }
    {
        const char *c = "{\"cmd\":\"t.ping\"}";
        unsigned char b[64];
        duo__u32(b, 9);
        memcpy(b + 4, c, strlen(c));
        send_app(0x05, b, 4 + (int)strlen(c));
        n = receive(0x85, buf, sizeof buf);
        assert(n == 13 && duo__get_u32(buf + 4) == 9 && buf[8] == DUO_OK);
    }
    {
        const char *s = "{\"keys\":[\"t.hp\"]}";
        send_app(0x04, s, (int)strlen(s));
        n = receive(0x84, buf, sizeof buf);
        buf[n] = 0;
        assert(buf[4] == 1 && strcmp((char *)buf + 8, "{\"t.hp\":5}") == 0);
        duo_publish("t.hp", "6");
        duo_publish("t.zone", "\"Greenpath\""); /* not subscribed: never sent */
        usleep(40000);
        n = receive(0x84, buf, sizeof buf);
        buf[n] = 0;
        assert(buf[4] == 0 && strcmp((char *)buf + 8, "{\"t.hp\":6}") == 0);
    }
    duo_stop();
    printf("duo.h: all checks passed\n");
    return 0;
}
