/* Synthetic installer-flow fixture. No OpenRad, driver, registry, or VPN APIs. */
#include <windows.h>
#include <stdio.h>
#include <wchar.h>

static int counter(const wchar_t *directory, const wchar_t *name) {
    wchar_t path[4096];
    swprintf(path, 4096, L"%ls\\%ls", directory, name);
    int value = 0;
    FILE *file = _wfopen(path, L"r");
    if (file) {
        fscanf(file, "%d", &value);
        fclose(file);
    }
    file = _wfopen(path, L"w");
    if (!file) return 1;
    fprintf(file, "%d", value + 1);
    fclose(file);
    return 0;
}

int wmain(int argc, wchar_t **argv) {
    wchar_t executable[4096];
    GetModuleFileNameW(NULL, executable, 4096);
    if (wcsstr(executable, L"openrad-desktop.exe")) {
        wchar_t *separator = wcsrchr(executable, L'\\');
        if (separator) *separator = 0;
        return counter(executable, L"desktop-count.txt");
    }
    for (int i = 1; i < argc; ++i) {
        if (!wcscmp(argv[i], L"prepare-radmin")) {
            int result = counter(L"C:", L"radmin-prepare-count.txt");
            if (result) return result;
            return GetFileAttributesW(L"C:\\radmin-prepare-denied.txt") == INVALID_FILE_ATTRIBUTES ? 0 : 1;
        }
    }
    const wchar_t *directory = NULL;
    for (int i = 1; i + 1 < argc; ++i) {
        if (!wcscmp(argv[i], L"--install-dir")) directory = argv[i + 1];
    }
    if (!directory) return 1;
    wchar_t marker[4096];
    swprintf(marker, 4096, L"%ls\\configured-count.txt", directory);
    for (int i = 1; i < argc; ++i) {
        if (!wcscmp(argv[i], L"check")) {
            return GetFileAttributesW(marker) == INVALID_FILE_ATTRIBUTES ? 10 : 0;
        }
        if (!wcscmp(argv[i], L"configure")) {
            if (GetFileAttributesW(L"C:\\radmin-prepare-count.txt") == INVALID_FILE_ATTRIBUTES) return 1;
            return counter(directory, L"configured-count.txt");
        }
        if (!wcscmp(argv[i], L"remove-adapter")) return 0;
    }
    return 1;
}
