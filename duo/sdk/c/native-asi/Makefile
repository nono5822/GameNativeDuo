# Builds GameNativeDuo-template.asi with mingw-w64 (Linux: pacman -S mingw-w64-gcc / apt install gcc-mingw-w64-x86-64).
# MSVC: cl /LD /O2 dllmain.c ws2_32.lib /Fe:GameNativeDuo-template.asi
CC ?= x86_64-w64-mingw32-gcc
GameNativeDuo-template.asi: dllmain.c ../duo.h
	$(CC) -shared -O2 -Wall -o $@ dllmain.c -lws2_32 -static-libgcc
clean:
	rm -f GameNativeDuo-template.asi
