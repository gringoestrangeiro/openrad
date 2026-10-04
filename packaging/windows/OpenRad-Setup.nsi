; Offline installer. Driver setup runs in a native x64, short-lived worker.
Unicode true
RequestExecutionLevel admin
ManifestSupportedOS Win10
Name "OpenRad ${OPENRAD_VERSION}"
OutFile "${SETUP_EXE}"
; An empty default lets .onInit distinguish native /D= overrides from a
; registry/default location. NSIS removes /D= from $CMDLINE before .onInit.
InstallDir ""
SetCompressor /SOLID lzma
SetCompressorDictSize 32
AutoCloseWindow true
ShowInstDetails show
ShowUninstDetails show
VIProductVersion "${OPENRAD_VERSION}.0"
VIAddVersionKey "ProductName" "OpenRad"
VIAddVersionKey "FileDescription" "OpenRad offline setup and launcher"
VIAddVersionKey "FileVersion" "${OPENRAD_VERSION}"
VIAddVersionKey "LegalCopyright" "OpenRad contributors; bundled components retain their licenses"

!include "LogicLib.nsh"
!include "FileFunc.nsh"
!include "x64.nsh"
!include "WinVer.nsh"

Var NoLaunch
Var Params
Var Worker
Var ErrorFile
Var SetupMutex

Page instfiles
UninstPage uninstConfirm
UninstPage instfiles

Function ReadWorkerError
    StrCpy $1 "OpenRad setup could not complete. Please retry after restarting Windows."
    ClearErrors
    FileOpen $0 "$ErrorFile" r
    ${IfNot} ${Errors}
        FileReadUTF16LE $0 $1
        FileClose $0
    ${EndIf}
FunctionEnd

Function LaunchDesktop
    ${If} $NoLaunch == 1
        Return
    ${EndIf}
    FindWindow $0 "" "OpenRad"
    ${If} $0 != 0
        ; Restore/focus an existing desktop instead of hitting its profile lock.
        System::Call 'user32::ShowWindow(p r0, i 9)'
        System::Call 'user32::SetForegroundWindow(p r0)'
    ${Else}
        SetOutPath "$INSTDIR"
        ClearErrors
        Exec '"$INSTDIR\openrad-desktop.exe"'
        ${If} ${Errors}
            MessageBox MB_OK|MB_ICONSTOP "OpenRad was installed, but its desktop could not be opened. Open it from the Start menu."
            SetErrorLevel 1
        ${EndIf}
    ${EndIf}
FunctionEnd

Function .onInit
    ${IfNot} ${IsNativeAMD64}
        MessageBox MB_OK|MB_ICONSTOP "This OpenRad package requires Windows 10 or 11 on an x64 PC."
        SetErrorLevel 1
        Quit
    ${EndIf}
    ${IfNot} ${AtLeastWin10}
        MessageBox MB_OK|MB_ICONSTOP "This OpenRad package requires Windows 10 or 11."
        SetErrorLevel 1
        Quit
    ${EndIf}
    System::Call 'kernel32::CreateMutexW(p 0, i 0, w "Global\OpenRadSetup") p .r0 ?e'
    Pop $1
    StrCpy $SetupMutex $0
    ${If} $1 == 183
        MessageBox MB_OK|MB_ICONINFORMATION "OpenRad setup is already running. Finish that setup first."
        SetErrorLevel 1
        Quit
    ${EndIf}
    StrCpy $NoLaunch 0
    ${GetParameters} $Params
    ClearErrors
    ${GetOptions} $Params "--no-launch" $0
    ${IfNot} ${Errors}
        StrCpy $NoLaunch 1
    ${EndIf}
    ClearErrors
    ${GetOptions} $Params "--help" $0
    ${IfNot} ${Errors}
        MessageBox MB_OK|MB_ICONINFORMATION "OpenRad setup installs the application and its TAP adapter, then opens the desktop.$\r$\n$\r$\n--no-launch: install/check setup without opening the desktop.$\r$\n--repair: reinstall files and repair the dedicated adapter.$\r$\n/S --no-launch: unattended setup without opening the desktop.$\r$\n/D=PATH: custom application folder; place this last, without quotes.$\r$\n$\r$\nThe driver is installed into Windows on each PC."
        SetErrorLevel 0
        Quit
    ${EndIf}
    SetRegView 64
    SetShellVarContext all
    ${If} $INSTDIR == ""
        ReadRegStr $0 HKLM "Software\OpenRad" "InstallLocation"
        ${If} $0 != ""
            StrCpy $INSTDIR $0
        ${Else}
            StrCpy $INSTDIR "$PROGRAMFILES64\OpenRad"
        ${EndIf}
    ${EndIf}
    InitPluginsDir
    SetOutPath "$PLUGINSDIR"
    File /oname=setup-worker.exe "${PAYLOAD_DIR}/openrad-setup-helper.exe"
    File /oname=expected-manifest.json "${PAYLOAD_DIR}/INSTALL-MANIFEST.json"
    StrCpy $Worker "$PLUGINSDIR\setup-worker.exe"
    StrCpy $ErrorFile "$PLUGINSDIR\setup-error.txt"
    ; Run before both first-time TAP creation and the already-ready launch path.
    DetailPrint "Closing Radmin VPN and disabling its official adapter..."
    ClearErrors
    ExecWait '"$Worker" --error-file "$ErrorFile" prepare-radmin' $0
    ${If} ${Errors}
        MessageBox MB_OK|MB_ICONSTOP "The Radmin VPN preparation worker could not start."
        SetErrorLevel 1
        Quit
    ${EndIf}
    ${If} $0 != 0
        Call ReadWorkerError
        MessageBox MB_OK|MB_ICONSTOP "$1"
        SetErrorLevel 1
        Quit
    ${EndIf}
    ClearErrors
    ${GetOptions} $Params "--repair" $0
    ${If} ${Errors}
        ClearErrors
        ExecWait '"$Worker" --error-file "$ErrorFile" check --install-dir "$INSTDIR" --manifest "$PLUGINSDIR\expected-manifest.json"' $0
        ${If} ${Errors}
            MessageBox MB_OK|MB_ICONSTOP "The Windows setup worker could not start."
            SetErrorLevel 1
            Quit
        ${EndIf}
        ${If} $0 == 0
            ReadRegStr $1 HKLM "Software\OpenRad" "InstallLocation"
            ${If} $1 != $INSTDIR
                Goto installation_required
            ${EndIf}
            ReadRegStr $1 HKLM "Software\OpenRad" "Version"
            ${If} $1 != "${OPENRAD_VERSION}"
                Goto installation_required
            ${EndIf}
            IfFileExists "$INSTDIR\Uninstall-OpenRad.exe" 0 installation_required
            IfSilent already_launch
            ${If} $NoLaunch == 1
                MessageBox MB_OK|MB_ICONINFORMATION "OpenRad is already installed and set up. Desktop launch was skipped (--no-launch)."
            ${Else}
                MessageBox MB_OK|MB_ICONINFORMATION "OpenRad is already installed and set up. Opening OpenRad Desktop."
            ${EndIf}
            already_launch:
            SetErrorLevel 0
            Call LaunchDesktop
            Quit
        ${ElseIf} $0 != 10
            Call ReadWorkerError
            MessageBox MB_OK|MB_ICONSTOP "$1"
            SetErrorLevel 1
            Quit
        ${EndIf}
    ${EndIf}
    installation_required:
    FindWindow $0 "" "OpenRad"
    ${If} $0 != 0
        MessageBox MB_OK|MB_ICONEXCLAMATION "Close OpenRad Desktop before installing an update or repairing setup."
        SetErrorLevel 1
        Quit
    ${EndIf}
FunctionEnd

Section "OpenRad" Main
    SetOutPath "$INSTDIR"
    SetOverwrite on
    File /r "${PAYLOAD_DIR}/*"
    DetailPrint "Setting up the dedicated OpenRad TAP-Windows6 adapter..."
    ClearErrors
    ExecWait '"$Worker" --error-file "$ErrorFile" configure --install-dir "$INSTDIR"' $0
    ${If} ${Errors}
        MessageBox MB_OK|MB_ICONSTOP "The Windows setup worker could not start."
        SetErrorLevel 1
        Abort
    ${EndIf}
    ${If} $0 == 3010
        SetRebootFlag true
    ${ElseIf} $0 != 0
        Call ReadWorkerError
        MessageBox MB_OK|MB_ICONSTOP "$1"
        SetErrorLevel 1
        Abort
    ${EndIf}
    WriteUninstaller "$INSTDIR\Uninstall-OpenRad.exe"
    WriteRegStr HKLM "Software\OpenRad" "InstallLocation" "$INSTDIR"
    WriteRegStr HKLM "Software\OpenRad" "Version" "${OPENRAD_VERSION}"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\OpenRad" "DisplayName" "OpenRad"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\OpenRad" "DisplayVersion" "${OPENRAD_VERSION}"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\OpenRad" "InstallLocation" "$INSTDIR"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\OpenRad" "UninstallString" '$\"$INSTDIR\Uninstall-OpenRad.exe$\"'
    WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\OpenRad" "NoModify" 1
    WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\OpenRad" "NoRepair" 1
    CreateDirectory "$SMPROGRAMS\OpenRad"
    CreateShortCut "$SMPROGRAMS\OpenRad\OpenRad Desktop.lnk" "$INSTDIR\openrad-desktop.exe"
    CreateShortCut "$SMPROGRAMS\OpenRad\OpenRad CLI.lnk" "$INSTDIR\Launch-CLI.cmd"
    CreateShortCut "$SMPROGRAMS\OpenRad\OpenRad Diagnostics.lnk" "$INSTDIR\Debug-OpenRad.cmd"
    CreateShortCut "$SMPROGRAMS\OpenRad\Uninstall OpenRad.lnk" "$INSTDIR\Uninstall-OpenRad.exe"
    CreateShortCut "$DESKTOP\OpenRad.lnk" "$INSTDIR\openrad-desktop.exe"
SectionEnd

Function .onInstSuccess
    IfRebootFlag needs_restart
    Call LaunchDesktop
    Return
    needs_restart:
        MessageBox MB_OK|MB_ICONINFORMATION "OpenRad was installed. Windows requests a restart to finish driver setup. Restart Windows, then run OpenRad setup again."
        SetErrorLevel 3010
FunctionEnd

Function un.onInit
    SetRegView 64
    SetShellVarContext all
    FindWindow $0 "" "OpenRad"
    ${If} $0 != 0
        MessageBox MB_OK|MB_ICONEXCLAMATION "Disconnect and close OpenRad Desktop before uninstalling. Stop any OpenRad CLI background process too."
        Abort
    ${EndIf}
FunctionEnd

Section "Uninstall"
    ClearErrors
    ExecWait '"$INSTDIR\openrad-setup-helper.exe" --error-file "$TEMP\openrad-uninstall-error.txt" remove-adapter --install-dir "$INSTDIR"' $0
    ${If} ${Errors}
        MessageBox MB_OK|MB_ICONSTOP "The OpenRad adapter could not be removed. Installation files were retained; retry after restarting Windows."
        Abort
    ${EndIf}
    ${If} $0 == 3010
        SetRebootFlag true
    ${ElseIf} $0 != 0
        MessageBox MB_OK|MB_ICONSTOP "The OpenRad adapter could not be removed. Installation files were retained; retry after restarting Windows."
        Abort
    ${EndIf}
    ; Generated exact-file deletion list; never recursively delete user files.
    !include "${DELETE_INCLUDE}"
    Delete "$INSTDIR\ADAPTER-STATE.json"
    Delete "$INSTDIR\Uninstall-OpenRad.exe"
    Delete "$DESKTOP\OpenRad.lnk"
    Delete "$SMPROGRAMS\OpenRad\OpenRad Desktop.lnk"
    Delete "$SMPROGRAMS\OpenRad\OpenRad CLI.lnk"
    Delete "$SMPROGRAMS\OpenRad\OpenRad Diagnostics.lnk"
    Delete "$SMPROGRAMS\OpenRad\Uninstall OpenRad.lnk"
    RMDir "$SMPROGRAMS\OpenRad"
    RMDir "$INSTDIR"
    DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\OpenRad"
    DeleteRegKey HKLM "Software\OpenRad"
    IfRebootFlag 0 +2
        MessageBox MB_OK|MB_ICONINFORMATION "Restart Windows to finish removing the OpenRad adapter. Your identities and profiles were preserved."
SectionEnd
