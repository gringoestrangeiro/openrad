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
; Installer icon. The offline packager (scripts/package-windows-installer.py)
; drops assets/logo.ico into the payload; without it the default icon is kept.
; (Classic UI, no MUI: `Icon` is the effective directive; MUI_ICON would be inert.)
!if /FileExists "${PAYLOAD_DIR}\logo.ico"
  Icon "${PAYLOAD_DIR}\logo.ico"
!endif

!include "LogicLib.nsh"
!include "FileFunc.nsh"
!include "x64.nsh"
!include "WinVer.nsh"
!include "TextFunc.nsh"

Var NoLaunch
Var Params
Var Worker
Var ErrorFile
Var SetupMutex
Var StopApproved
Var SetupLog

Page instfiles
UninstPage uninstConfirm
UninstPage instfiles

; Persistent setup log, beside the desktop/CLI startup logs (docs/windows.md):
; %LOCALAPPDATA%\OpenRad\logs\setup.log of the elevated account, else
; %TEMP%\OpenRad\logs. Every step, worker exit code, worker error and dialog is
; appended before it happens, so a hidden or crashed setup still leaves a
; trace. UTF-16LE with a BOM keeps Unicode paths; Notepad opens it directly.
; It contains paths and exit codes only, never credentials.
!define OPENRAD_LOG_LIMIT 1048576

!macro OpenRadLog TEXT
    Push `${TEXT}`
    !ifdef __UNINSTALL__
        Call un.OpenRadLogLine
    !else
        Call OpenRadLogLine
    !endif
!macroend
!define Log "!insertmacro OpenRadLog"

; Logs the reason, then sets the exit code and quits from .onInit.
!macro OpenRadQuit CODE REASON
    ${Log} `Setup finished; exit code ${CODE}: ${REASON}`
    SetErrorLevel ${CODE}
    Quit
!macroend

; Serialize installation, repair and removal across user sessions. Retain the
; handle until process exit. A failed CreateMutex must not allow mutations.
!macro OpenRadAcquireSetupMutex STOP
    System::Call 'kernel32::CreateMutexW(p 0, i 0, w "Global\OpenRadSetup") p .r0 ?e'
    Pop $1
    StrCpy $SetupMutex $0
    ${If} $SetupMutex == 0
        ${Log} "Cannot acquire setup/uninstall mutex; Windows error $1"
        MessageBox MB_OK|MB_ICONSTOP "OpenRad could not lock installation changes. Close any other setup or uninstaller and try again." /SD IDOK
        SetErrorLevel 1
        ${STOP}
    ${EndIf}
    ${If} $1 == 183
        ${Log} "Another OpenRad setup or uninstall is already running"
        MessageBox MB_OK|MB_ICONINFORMATION "OpenRad setup or uninstall is already running. Finish that operation first." /SD IDOK
        SetErrorLevel 1
        ${STOP}
    ${EndIf}
!macroend

!macro OpenRadLogFunctions UN
Function ${UN}OpenRadLogOpen
    ; Call before SetShellVarContext all, which maps $LOCALAPPDATA to ProgramData.
    Push $0
    Push $1
    Push $2
    StrCpy $2 "$LOCALAPPDATA\OpenRad\logs"
    ${For} $1 1 2
        ${If} $1 == 2
            StrCpy $2 "$TEMP\OpenRad\logs"
        ${EndIf}
        CreateDirectory "$2"
        ; Keep one previous log, like the early startup logs.
        ClearErrors
        FileOpen $0 "$2\setup.log" r
        ${IfNot} ${Errors}
            FileSeek $0 0 END $SetupLog
            FileClose $0
            ${If} $SetupLog >= ${OPENRAD_LOG_LIMIT}
                Delete "$2\setup.previous.log"
                Rename "$2\setup.log" "$2\setup.previous.log"
            ${EndIf}
        ${EndIf}
        StrCpy $SetupLog ""
        ClearErrors
        ${If} ${FileExists} "$2\setup.log"
            FileOpen $0 "$2\setup.log" a
        ${Else}
            FileOpen $0 "$2\setup.log" w
            ${IfNot} ${Errors}
                FileWriteWord $0 0xFEFF
            ${EndIf}
        ${EndIf}
        ${IfNot} ${Errors}
            FileClose $0
            StrCpy $SetupLog "$2\setup.log"
            ${Break}
        ${EndIf}
    ${Next}
    ClearErrors
    Pop $2
    Pop $1
    Pop $0
FunctionEnd

Function ${UN}OpenRadLogLine
    ; Preserves all registers and the error flag, so a log line can sit
    ; between an instruction and its ${Errors} check.
    Exch $R9
    Push $R0
    Push $R1
    Push $R2
    Push $R3
    Push $R4
    Push $R5
    Push $R6
    Push $R7
    Push $R8
    StrCpy $R7 0
    IfErrors 0 +2
        StrCpy $R7 1
    ${If} $SetupLog != ""
        ${GetTime} "" "L" $R0 $R1 $R2 $R3 $R4 $R5 $R6
        ClearErrors
        FileOpen $R8 "$SetupLog" a
        ${IfNot} ${Errors}
            FileSeek $R8 0 END
            FileWriteUTF16LE $R8 "$R2-$R1-$R0 $R4:$R5:$R6  $R9$\r$\n"
            FileClose $R8
        ${EndIf}
    ${EndIf}
    ClearErrors
    ${If} $R7 == 1
        SetErrors
    ${EndIf}
    Pop $R8
    Pop $R7
    Pop $R6
    Pop $R5
    Pop $R4
    Pop $R3
    Pop $R2
    Pop $R1
    Pop $R0
    Pop $R9
FunctionEnd

Function ${UN}OpenRadLogEnvironment
    Push $0
    Push $1
    Push $2
    ${WinVerGetMajor} $0
    ${WinVerGetMinor} $1
    ${WinVerGetBuild} $2
    ${Log} "Windows $0.$1 build $2"
    ReadEnvStr $0 USERNAME
    System::Call 'shell32::IsUserAnAdmin() i .r1'
    ${Log} "Account $0; administrator=$1; log=$SetupLog"
    Pop $2
    Pop $1
    Pop $0
FunctionEnd

Function ${UN}ReadWorkerError
    ; $1 = first error line, for the dialog. Every line goes to the log: a
    ; PowerShell error can span several lines.
    Push $0
    Push $2
    Push $3
    StrCpy $1 "OpenRad setup could not complete. Please retry after restarting Windows."
    ClearErrors
    FileOpen $0 "$ErrorFile" r
    ${If} ${Errors}
        ${Log} "Worker left no error text in $ErrorFile"
    ${Else}
        StrCpy $3 0
        ${Do}
            ClearErrors
            FileReadUTF16LE $0 $2
            ${If} ${Errors}
                ${Break}
            ${EndIf}
            ${If} $3 == 0
                StrCpy $1 $2
            ${EndIf}
            IntOp $3 $3 + 1
            ${TrimNewLines} "$2" $2
            ${Log} "Worker error: $2"
        ${Loop}
        FileClose $0
    ${EndIf}
    ClearErrors
    Pop $3
    Pop $2
    Pop $0
FunctionEnd
!macroend
!insertmacro OpenRadLogFunctions ""
!insertmacro OpenRadLogFunctions "un."

Function LaunchDesktop
    ${If} $NoLaunch == 1
        ${Log} "Desktop launch skipped (--no-launch)"
        Return
    ${EndIf}
    FindWindow $0 "" "OpenRad"
    ${If} $0 != 0
        ; Restore/focus an existing desktop instead of hitting its profile lock.
        ${Log} "Desktop window already open; bringing it to the front"
        System::Call 'user32::ShowWindow(p r0, i 9)'
        System::Call 'user32::SetForegroundWindow(p r0)'
    ${Else}
        SetOutPath "$INSTDIR"
        ${Log} "Starting $INSTDIR\openrad-desktop.exe; its own log is desktop-startup.log"
        ClearErrors
        Exec '"$INSTDIR\openrad-desktop.exe"'
        ${If} ${Errors}
            ${Log} "Dialog: the desktop could not be started"
            MessageBox MB_OK|MB_ICONSTOP "OpenRad was installed, but its desktop could not be opened. Open it from the Start menu." /SD IDOK
            SetErrorLevel 1
        ${EndIf}
    ${EndIf}
FunctionEnd

!define WM_CLOSE 0x0010

!macro OpenRadProbeLocked NAME
    ; A running image cannot be opened for writing, so this reports only the
    ; processes that would block this copy: other users' services or portable
    ; copies outside $INSTDIR are not affected.
    ${If} $R0 == 0
    ${AndIf} ${FileExists} "$INSTDIR\${NAME}"
        ClearErrors
        FileOpen $1 "$INSTDIR\${NAME}" a
        ${If} ${Errors}
            StrCpy $R0 1
        ${Else}
            FileClose $1
        ${EndIf}
    ${EndIf}
!macroend

Function ProbeOpenRadRunning
    ; $R0 = 1 while the desktop window exists or an installed executable is in
    ; use; 0 otherwise. Nothing is stopped here.
    StrCpy $R0 0
    FindWindow $0 "" "OpenRad"
    ${If} $0 != 0
        StrCpy $R0 1
        Return
    ${EndIf}
    !insertmacro OpenRadProbeLocked "openrad.exe"
    !insertmacro OpenRadProbeLocked "openrad-desktop.exe"
    !insertmacro OpenRadProbeLocked "openrad-setup-helper.exe"
    ClearErrors
FunctionEnd

Function StopOpenRadGracefully
    ; Only after the user approved it (or /S requested an unattended update).
    ; Runs the payload's own openrad.exe from $PLUGINSDIR: an existing
    ; $INSTDIR\openrad.exe may sit in a /D= or recorded folder that a standard
    ; user can write, and this setup is elevated. `openrad stop` is the
    ; documented Stop, so the service removes its TAP routes itself; no process
    ; is force-terminated.
    ${IfNot} ${FileExists} "$PLUGINSDIR\openrad-stop.exe"
        SetOutPath "$PLUGINSDIR"
        File /oname=openrad-stop.exe "${PAYLOAD_DIR}\openrad.exe"
    ${EndIf}
    ${Log} "Stopping OpenRad with the bundled openrad.exe stop"
    ClearErrors
    ExecWait '"$PLUGINSDIR\openrad-stop.exe" stop' $0
    ${Log} "openrad stop exit code: $0"
    ClearErrors
    ; The desktop handles WM_CLOSE like its own close button and exits once
    ; its backend is down.
    FindWindow $0 "" "OpenRad"
    ${If} $0 != 0
        ${Log} "Asking the open desktop window to close"
        SendMessage $0 ${WM_CLOSE} 0 0 /TIMEOUT=5000
    ${EndIf}
    Sleep 1000
FunctionEnd

Function ConfirmOpenRadClosed
    ; Ask before disconnecting anything, then offer Retry/Cancel while the
    ; desktop or an installed executable is still in use, instead of failing
    ; mid-copy inside File /r.
    Call ProbeOpenRadRunning
    ${If} $R0 == 0
        Return
    ${EndIf}
    ${Log} "OpenRad is running (desktop window or a locked installed executable)"
    IfSilent openrad_stop
    ${Log} "Dialog: asking to stop OpenRad"
    MessageBox MB_OKCANCEL|MB_ICONQUESTION "OpenRad is running. To continue, setup will disconnect the VPN, stop the OpenRad service and close OpenRad Desktop.$\r$\n$\r$\nChoose OK to stop OpenRad and continue, or Cancel to quit setup." IDOK openrad_stop
    Goto openrad_cancel
  openrad_stop:
    StrCpy $StopApproved 1
    Call StopOpenRadGracefully
  openrad_check:
    Call ProbeOpenRadRunning
    ${If} $R0 == 0
        Return
    ${EndIf}
    ${Log} "OpenRad is still running after stop"
    IfSilent openrad_cancel
    ${Log} "Dialog: OpenRad is still running (Retry/Cancel)"
    MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION "OpenRad is still running. Close OpenRad Desktop and stop the background service before installing (another Windows user may be running it).$\r$\n$\r$\nIn the installed folder run: openrad stop$\r$\nThen choose Retry. Choose Cancel to quit setup.$\r$\n$\r$\nDo NOT choose Ignore on any file-write error: a skipped file fails the integrity check after copying and forces a reinstall." IDRETRY openrad_retry
  openrad_cancel:
    !insertmacro OpenRadQuit 1 "OpenRad was not stopped"
  openrad_retry:
    Call StopOpenRadGracefully
    Goto openrad_check
FunctionEnd

Function .onInit
    ; First, before any check or dialog can end setup.
    Call OpenRadLogOpen
    ${Log} "==== OpenRad setup ${OPENRAD_VERSION} started ===="
    ${Log} "Command line: $CMDLINE"
    Call OpenRadLogEnvironment
    ${If} ${Silent}
        ${Log} "Silent mode (/S)"
    ${EndIf}
    ${IfNot} ${IsNativeAMD64}
        ${Log} "Dialog: not an x64 Windows PC"
        MessageBox MB_OK|MB_ICONSTOP "This OpenRad package requires Windows 10 or 11 on an x64 PC." /SD IDOK
        !insertmacro OpenRadQuit 1 "not native x64 Windows"
    ${EndIf}
    ${IfNot} ${AtLeastWin10}
        ${Log} "Dialog: Windows older than 10"
        MessageBox MB_OK|MB_ICONSTOP "This OpenRad package requires Windows 10 or 11." /SD IDOK
        !insertmacro OpenRadQuit 1 "Windows older than 10"
    ${EndIf}
    !insertmacro OpenRadAcquireSetupMutex Quit
    StrCpy $NoLaunch 0
    StrCpy $StopApproved 0
    ${GetParameters} $Params
    ClearErrors
    ${GetOptions} $Params "--no-launch" $0
    ${IfNot} ${Errors}
        StrCpy $NoLaunch 1
    ${EndIf}
    ClearErrors
    ${GetOptions} $Params "--help" $0
    ${IfNot} ${Errors}
        ${Log} "Dialog: --help"
        MessageBox MB_OK|MB_ICONINFORMATION "OpenRad setup installs the application and its TAP adapter, then opens the desktop.$\r$\n$\r$\n--no-launch: install/check setup without opening the desktop.$\r$\n--repair: reinstall files and repair the dedicated adapter.$\r$\n/S --no-launch: unattended setup without opening the desktop.$\r$\n/D=PATH: custom application folder; place this last, without quotes.$\r$\n$\r$\nThe driver is installed into Windows on each PC."
        !insertmacro OpenRadQuit 0 "--help shown"
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
    ${Log} "Install folder: $INSTDIR"
    InitPluginsDir
    SetOutPath "$PLUGINSDIR"
    File /oname=setup-worker.exe "${PAYLOAD_DIR}\openrad-setup-helper.exe"
    File /oname=expected-manifest.json "${PAYLOAD_DIR}\INSTALL-MANIFEST.json"
    StrCpy $Worker "$PLUGINSDIR\setup-worker.exe"
    StrCpy $ErrorFile "$PLUGINSDIR\setup-error.txt"
    ; Run before both first-time TAP creation and the already-ready launch path.
    DetailPrint "Closing Radmin VPN and disabling its official adapter..."
    ${Log} "Worker prepare-radmin: closing Radmin VPN and disabling its adapter"
    ClearErrors
    ExecWait '"$Worker" --error-file "$ErrorFile" prepare-radmin' $0
    ${If} ${Errors}
        ${Log} "Dialog: the Radmin VPN preparation worker could not start"
        MessageBox MB_OK|MB_ICONSTOP "The Radmin VPN preparation worker could not start." /SD IDOK
        !insertmacro OpenRadQuit 1 "prepare-radmin worker did not start"
    ${EndIf}
    ${Log} "Worker prepare-radmin exit code: $0"
    ${If} $0 != 0
        Call ReadWorkerError
        ${Log} "Dialog: Radmin VPN preparation failed"
        MessageBox MB_OK|MB_ICONSTOP "$1" /SD IDOK
        !insertmacro OpenRadQuit 1 "Radmin VPN preparation failed"
    ${EndIf}
    ClearErrors
    ${GetOptions} $Params "--repair" $0
    ${If} ${Errors}
        ${Log} "Worker check: is this version already installed and its adapter ready?"
        ClearErrors
        ExecWait '"$Worker" --error-file "$ErrorFile" check --install-dir "$INSTDIR" --manifest "$PLUGINSDIR\expected-manifest.json"' $0
        ${If} ${Errors}
            ${Log} "Dialog: the check worker could not start"
            MessageBox MB_OK|MB_ICONSTOP "The Windows setup worker could not start." /SD IDOK
            !insertmacro OpenRadQuit 1 "check worker did not start"
        ${EndIf}
        ${Log} "Worker check exit code: $0 (0 ready, 10 install needed)"
        ${If} $0 == 0
            ReadRegStr $1 HKLM "Software\OpenRad" "InstallLocation"
            ${If} $1 != $INSTDIR
                ${Log} "Install needed: registered folder is '$1'"
                Goto installation_required
            ${EndIf}
            ReadRegStr $1 HKLM "Software\OpenRad" "Version"
            ${If} $1 != "${OPENRAD_VERSION}"
                ${Log} "Install needed: registered version is '$1'"
                Goto installation_required
            ${EndIf}
            ${IfNot} ${FileExists} "$INSTDIR\Uninstall-OpenRad.exe"
                ${Log} "Install needed: the uninstaller is missing"
                Goto installation_required
            ${EndIf}
            ${Log} "Already installed and set up; skipping installation"
            IfSilent already_launch
            ${If} $NoLaunch == 1
                ${Log} "Dialog: already installed (--no-launch)"
                MessageBox MB_OK|MB_ICONINFORMATION "OpenRad is already installed and set up. Desktop launch was skipped (--no-launch)."
            ${Else}
                ${Log} "Dialog: already installed, opening the desktop"
                MessageBox MB_OK|MB_ICONINFORMATION "OpenRad is already installed and set up. Opening OpenRad Desktop."
            ${EndIf}
            already_launch:
            SetErrorLevel 0
            Call LaunchDesktop
            GetErrorLevel $0
            ${Log} "Setup finished; exit code $0: already installed"
            Quit
        ${ElseIf} $0 != 10
            Call ReadWorkerError
            ${Log} "Dialog: installation check failed"
            MessageBox MB_OK|MB_ICONSTOP "$1" /SD IDOK
            !insertmacro OpenRadQuit 1 "installation check failed"
        ${EndIf}
    ${Else}
        ${Log} "Repair requested (--repair)"
    ${EndIf}
    installation_required:
    Call ConfirmOpenRadClosed
FunctionEnd

Section "OpenRad" Main
    ; Re-check right before copying: a restart after .onInit would otherwise
    ; surface as NSIS "Error opening file for writing". Stop again only when
    ; the user already approved it there.
    ; Delete /REBOOTOK schedules a locked executable for removal on reboot and
    ; sets the reboot flag; a set flag aborts here with guidance instead of
    ; letting Ignore leave a half-updated install that fails the worker
    ; integrity check afterwards.
    ${Log} "Installing files"
    Call ProbeOpenRadRunning
    ${If} $R0 == 1
    ${AndIf} $StopApproved == 1
        DetailPrint "Stopping OpenRad..."
        Call StopOpenRadGracefully
        Call ProbeOpenRadRunning
    ${EndIf}
    ${If} $R0 == 1
        ${Log} "Dialog: OpenRad started again during setup"
        MessageBox MB_OK|MB_ICONSTOP "OpenRad started again during setup. Disconnect and close OpenRad, then run setup again." /SD IDOK
        SetErrorLevel 1
        Abort
    ${EndIf}
    ClearErrors
    Delete /REBOOTOK "$INSTDIR\openrad.exe"
    ClearErrors
    Delete /REBOOTOK "$INSTDIR\openrad-desktop.exe"
    ClearErrors
    Delete /REBOOTOK "$INSTDIR\openrad-setup-helper.exe"
    ClearErrors
    IfRebootFlag 0 locked_reboot_done
        ${Log} "Dialog: a previous OpenRad file is still locked; restart required"
        MessageBox MB_OK|MB_ICONINFORMATION "A previous OpenRad file is still locked by Windows. Restart Windows and run setup again to finish the update. Do not click Ignore on file-write errors."
        Abort
    locked_reboot_done:
    SetOutPath "$INSTDIR"
    SetOverwrite on
    File /r "${PAYLOAD_DIR}\*"
    ${Log} "Files copied"
    DetailPrint "Setting up the dedicated OpenRad TAP-Windows6 adapter..."
    ${Log} "Worker configure: setting up the TAP-Windows6 adapter"
    ClearErrors
    ExecWait '"$Worker" --error-file "$ErrorFile" configure --install-dir "$INSTDIR"' $0
    ${If} ${Errors}
        ${Log} "Dialog: the configure worker could not start"
        MessageBox MB_OK|MB_ICONSTOP "The Windows setup worker could not start." /SD IDOK
        SetErrorLevel 1
        Abort
    ${EndIf}
    ${Log} "Worker configure exit code: $0 (0 ready, 3010 restart needed)"
    ${If} $0 == 3010
        SetRebootFlag true
    ${ElseIf} $0 != 0
        Call ReadWorkerError
        ${Log} "Dialog: adapter setup failed"
        MessageBox MB_OK|MB_ICONSTOP "$1" /SD IDOK
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
    CreateShortCut "$SMPROGRAMS\OpenRad\OpenRad Desktop.lnk" "$INSTDIR\openrad-desktop.exe" "" "$INSTDIR\logo.ico"
    CreateShortCut "$SMPROGRAMS\OpenRad\OpenRad CLI.lnk" "$INSTDIR\Launch-CLI.cmd"
    CreateShortCut "$SMPROGRAMS\OpenRad\OpenRad Diagnostics.lnk" "$INSTDIR\Debug-OpenRad.cmd"
    CreateShortCut "$SMPROGRAMS\OpenRad\Uninstall OpenRad.lnk" "$INSTDIR\Uninstall-OpenRad.exe"
    ; The first-run wizard offers this shortcut as an opt-in.
    ; Do not create it unconditionally during installation.
    ${Log} "Registered the installation and Start menu shortcuts"
SectionEnd

Function .onInstSuccess
    IfRebootFlag needs_restart
    Call LaunchDesktop
    GetErrorLevel $0
    ${If} $0 == -1
        StrCpy $0 0 ; unset: NSIS exits 0 after success
    ${EndIf}
    ${Log} "Setup finished; exit code $0: installed"
    Return
    needs_restart:
        ${Log} "Dialog: restart Windows to finish driver setup"
        MessageBox MB_OK|MB_ICONINFORMATION "OpenRad was installed. Windows requests a restart to finish driver setup. Restart Windows, then run OpenRad setup again."
        SetErrorLevel 3010
        ${Log} "Setup finished; exit code 3010: installed, restart required"
FunctionEnd

Function .onInstFailed
    GetErrorLevel $0
    ${If} $0 == -1
        StrCpy $0 2 ; unset: NSIS exits 2 after a script abort
    ${EndIf}
    ${Log} "Setup finished; exit code $0: installation aborted"
FunctionEnd

Function un.onInit
    Call un.OpenRadLogOpen
    ${Log} "==== OpenRad uninstall ${OPENRAD_VERSION} started ===="
    ${Log} "Command line: $CMDLINE"
    Call un.OpenRadLogEnvironment
    !insertmacro OpenRadAcquireSetupMutex Abort
    SetRegView 64
    SetShellVarContext all
    ; The Settings shortcut launches this process before the desktop finishes
    ; its graceful shutdown. Wait only for this explicit entry point.
    ${GetParameters} $Params
    ClearErrors
    ${GetOptions} $Params "/FROMSETTINGS" $0
    ${IfNot} ${Errors}
        StrCpy $0 120
      wait_for_desktop:
        FindWindow $1 "" "OpenRad"
        ${If} $1 != 0
            Sleep 250
            IntOp $0 $0 - 1
            ${If} $0 > 0
                Goto wait_for_desktop
            ${EndIf}
        ${EndIf}
    ${EndIf}
    FindWindow $0 "" "OpenRad"
    ${If} $0 != 0
        ${Log} "Dialog: close OpenRad Desktop before uninstalling"
        MessageBox MB_OK|MB_ICONEXCLAMATION "Disconnect and close OpenRad Desktop before uninstalling. Stop any OpenRad CLI background process too."
        ${Log} "Uninstall finished: the desktop was still open"
        Abort
    ${EndIf}
FunctionEnd

Function un.StopOpenRadForRemove
    ; Graceful `openrad stop` only, after the uninstall confirmation page; no
    ; process is force-terminated. Running $INSTDIR\openrad.exe adds no trust:
    ; this uninstaller and the setup helper below come from the same folder.
    ${If} ${FileExists} "$INSTDIR\openrad.exe"
        ${Log} "Stopping OpenRad with $INSTDIR\openrad.exe stop"
        ExecWait '"$INSTDIR\openrad.exe" stop' $0
        ${Log} "openrad stop exit code: $0"
    ${EndIf}
    ClearErrors
FunctionEnd

Section "Uninstall"
    ${Log} "Uninstalling from $INSTDIR"
    Call un.StopOpenRadForRemove
    StrCpy $ErrorFile "$TEMP\openrad-uninstall-error.txt"
    Delete "$ErrorFile"
    ${Log} "Worker remove-adapter: removing the OpenRad TAP adapter"
    ClearErrors
    ExecWait '"$INSTDIR\openrad-setup-helper.exe" --error-file "$ErrorFile" remove-adapter --install-dir "$INSTDIR"' $0
    ${If} ${Errors}
        ${Log} "Dialog: the remove-adapter worker could not start"
        MessageBox MB_OK|MB_ICONSTOP "The OpenRad adapter could not be removed. Installation files were retained; retry after restarting Windows."
        Abort
    ${EndIf}
    ${Log} "Worker remove-adapter exit code: $0 (0 removed, 3010 restart needed)"
    ${If} $0 == 3010
        SetRebootFlag true
    ${ElseIf} $0 != 0
        Call un.ReadWorkerError
        ${Log} "Dialog: the adapter could not be removed"
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
    ${Log} "Removed installed files and registration"
    ${If} ${RebootFlag}
        ${Log} "Dialog: restart Windows to finish removing the adapter"
        MessageBox MB_OK|MB_ICONINFORMATION "Restart Windows to finish removing the OpenRad adapter. Your identities and profiles were preserved."
    ${EndIf}
SectionEnd

Function un.onUninstSuccess
    ${Log} "Uninstall finished: removed"
FunctionEnd

Function un.onUninstFailed
    ${Log} "Uninstall finished: aborted; installation files were retained"
FunctionEnd
