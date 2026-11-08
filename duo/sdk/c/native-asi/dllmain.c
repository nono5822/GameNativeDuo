/*
 * GameNative Duo native mod template (MIT): an .asi plugin for Ultimate ASI Loader
 * (https://github.com/ThirteenAG/Ultimate-ASI-Loader, rename its dll to dinput8.dll / winmm.dll / ... next to the
 * game's exe, put this .asi beside it, and set WINEDLLOVERRIDES=<that dll>=n,b).
 *
 * What it shows: the bridge from a thread of its own (no hooks needed), one element for the layout editor, companion
 * state, a command, a log line. Replace read_game_state() with reads of the game's own public data (never DRM, never
 * save files: see the rules in duo/docs/devkit-platform.md). For per-frame work (drawing), call duo_tick from a hook on
 * the game's present instead of the thread.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#define DUO_IMPLEMENTATION
#include "../duo.h"

static volatile LONG running = 1;

/* Your game's data. A real mod reads it from the game (signature scan, exported functions, a scripting API). */
static int read_game_hp(void) { return 42; }

static void on_command(unsigned request, const char *json, void *user) {
    (void)user;
    if (strstr(json, "\"template.hello\""))
        duo_command_result(request, DUO_OK, "Hello from the game");
    else
        duo_command_result(request, DUO_UNKNOWN, "unknown command");
}

/* The layout editor asks where our elements are: window pixels, rules applied (contract: elements.json). */
static void on_dump(unsigned request, void *user) {
    const duo_layout *l = duo_get_layout();
    char json[512];
    (void)user;
    snprintf(json, sizeof json,
             "{\"engine\":\"native-template\",\"screen\":{\"w\":%d,\"h\":%d},\"elements\":["
             "{\"id\":\"Health\",\"name\":\"Health\",\"parent\":-1,\"screen\":\"bottom\",\"x\":%d,\"y\":%d,\"w\":200,\"h\":40,"
             "\"graphic\":true,\"features\":{\"scale\":false}}]}",
             l->screen_w, l->screen_h, l->bottom.x + 20, l->bottom.y + 20);
    duo_write_file("elements.json", json);
    duo_elements_written(request);
}

static DWORD WINAPI duo_thread(LPVOID arg) {
    RECT r;
    HWND window = NULL;
    (void)arg;
    if (!duo_start("native-template")) return 0;
    duo_on_command(on_command, NULL);
    duo_on_dump(on_dump, NULL);
    duo_log(DUO_INFO, "native template loaded");
    while (running) {
        char hp[16];
        if (!window) window = GetForegroundWindow();
        if (window && GetClientRect(window, &r)) duo_tick(r.right - r.left, r.bottom - r.top);
        else duo_tick(0, 0);
        if (duo_wants("template.hp")) {
            snprintf(hp, sizeof hp, "%d", read_game_hp());
            duo_publish("template.hp", hp);
        }
        Sleep(33);
    }
    duo_stop();
    return 0;
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(instance);
        CloseHandle(CreateThread(NULL, 0, duo_thread, NULL, 0, NULL));
    } else if (reason == DLL_PROCESS_DETACH) {
        InterlockedExchange(&running, 0);
    }
    return TRUE;
}
