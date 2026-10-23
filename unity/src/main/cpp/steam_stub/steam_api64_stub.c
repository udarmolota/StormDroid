// libsteam_api64.so on Android: no Steam client here. The game's Steamworks.NET (Windows build,
// P/Invoke "steam_api64") quits the game when the library cannot be loaded at all (SteamManager:
// DllNotFoundException -> Application.Quit). With this stub the library loads and simply answers
// "Steam is not running": RestartAppIfNecessary and Init return false, and the game goes on without
// Steam. Only the startup entry points exist; the game makes no other Steam call once Init failed.
#include <stddef.h>

#define API __attribute__((visibility("default")))

API int SteamAPI_RestartAppIfNecessary(unsigned int appId) { (void)appId; return 0; }
API int SteamAPI_Init(void) { return 0; }
API int SteamAPI_IsSteamRunning(void) { return 0; }
API void SteamAPI_Shutdown(void) {}
API void SteamAPI_RunCallbacks(void) {}
API void SteamAPI_ReleaseCurrentThreadMemory(void) {}
API void SteamAPI_ManualDispatch_Init(void) {}
API int SteamAPI_GetHSteamPipe(void) { return 0; }
API int SteamAPI_GetHSteamUser(void) { return 0; }
API const char* SteamAPI_GetSteamInstallPath(void) { return NULL; }
API int SteamInternal_GameServer_Init(unsigned int ip, unsigned short a, unsigned short b, unsigned short c, int mode, const char* version) {
    (void)ip; (void)a; (void)b; (void)c; (void)mode; (void)version; return 0;
}
