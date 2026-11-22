; 数艺工坊 Windows installer (Inno Setup 6). Built by cicd/desktop/build_installer.py, which
; passes the inputs below; never compile it by hand with a typed version.
;
;   AppVersion       the product version, from the root pom.xml revision (docs/versioning.md)
;   AppImage         the jpackage app image directory (desktop/target/dist/DatacraftLab)
;   SetupIcon        the icon package_desktop.py drew for that image
;   OutDir           where Setup.exe is written
;   ChineseMessages  the reviewed ChineseSimplified.isl
;   Fixture          optional: a test identity. The installer then installs per user, without
;                    administrator rights, into a folder of its own, under its own uninstall key,
;                    so the lifecycle check can run on a workstation without touching a real install.
;
; One AppId owns the machine installation, its upgrades and its 64-bit uninstall entry
; (HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\com.datacraft.lab_is1). Running the
; same version again repairs it in place; a lower version refuses to replace a higher one. Setup and
; uninstall refuse while 数艺工坊 runs (it holds the AppMutex). Nothing outside the installation
; folder and the shortcuts is written or removed: ~/.datacraft (settings and caches) survives an
; uninstall.

#ifndef AppVersion
  #error AppVersion is required
#endif
#ifndef AppImage
  #error AppImage is required
#endif
#ifndef SetupIcon
  #error SetupIcon is required
#endif
#ifndef OutDir
  #error OutDir is required
#endif
#ifndef ChineseMessages
  #error ChineseMessages is required
#endif

#define Product "数艺工坊"
#define ProgramFile "DatacraftLab.exe"
#ifdef Fixture
  #define Identity "com.datacraft.lab.fixture." + Fixture
  #define Folder "datacraft-install-" + Fixture
  #define Shortcut Product + " " + Fixture
#else
  #define Identity "com.datacraft.lab"
  #define Folder "DatacraftLab"
  #define Shortcut Product
#endif
#define UninstallKey "Software\Microsoft\Windows\CurrentVersion\Uninstall\" + Identity + "_is1"

#if !FileExists(AddBackslash(AppImage) + ProgramFile)
  #error AppImage holds no DatacraftLab.exe
#endif

[Setup]
AppId={#Identity}
AppName={#Product}
AppVersion={#AppVersion}
AppVerName={#Product} {#AppVersion}
AppPublisher={#Product}
AppCopyright=datacraft-lab
UninstallDisplayName={#Product}
UninstallDisplayIcon={app}\{#ProgramFile}
VersionInfoVersion={#AppVersion}
VersionInfoProductVersion={#AppVersion}
VersionInfoProductName={#Product}
VersionInfoCompany={#Product}
VersionInfoDescription={#Product} 安装程序
WizardStyle=modern
DisableWelcomePage=no
DisableDirPage=no
DisableProgramGroupPage=yes
DisableReadyPage=no
DisableFinishedPage=no
ArchitecturesAllowed=x64os
ArchitecturesInstallIn64BitMode=x64os
MinVersion=10.0
#ifdef Fixture
PrivilegesRequired=lowest
DefaultDirName={localappdata}\Temp\{#Folder}\DatacraftLab
DefaultGroupName={#Product} {#Fixture}
AppMutex={#Identity}
SetupMutex={#Identity}.setup
#else
PrivilegesRequired=admin
DefaultDirName={autopf}\{#Folder}
DefaultGroupName={#Product}
; The running app holds this mutex (desktop SingleInstance.MUTEX).
AppMutex=com.datacraft.lab
SetupMutex=Global\com.datacraft.lab.setup
#endif
UsePreviousAppDir=yes
UsePreviousTasks=yes
CloseApplications=no
RestartApplications=no
AllowCancelDuringInstall=no
SetupIconFile={#SetupIcon}
Compression=lzma2/max
SolidCompression=yes
OutputDir={#OutDir}
OutputBaseFilename=Setup
SetupLogging=yes

[Languages]
Name: "chinesesimp"; MessagesFile: "{#ChineseMessages}"

[Messages]
chinesesimp.SetupAppRunningError=数艺工坊正在运行。请先关闭它（包括最小化的窗口），再点击“确定”继续，或点击“取消”退出。
chinesesimp.UninstallAppRunningError=数艺工坊正在运行。请先关闭它（包括最小化的窗口），再点击“确定”继续，或点击“取消”退出。

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "快捷方式"; Flags: unchecked

[InstallDelete]
; A repair or upgrade replaces the program and its runtime as a whole, so no file of an older
; version (a renamed jar, an old AOT cache) is left behind. The folder check in [Code] has made sure
; {app} is this product's folder before anything is deleted.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#Shortcut}"; Filename: "{app}\{#ProgramFile}"; WorkingDir: "{app}"; AppUserModelID: "{#Identity}"
Name: "{autodesktop}\{#Shortcut}"; Filename: "{app}\{#ProgramFile}"; WorkingDir: "{app}"; AppUserModelID: "{#Identity}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#ProgramFile}"; Description: "启动数艺工坊"; Flags: nowait postinstall skipifsilent

[Code]
{ A release version is MAJOR.MINOR.PATCH with each part at most 65535 (docs/versioning.md). }
function PackVersion(Text: String; var Packed: Int64): Boolean;
var Rest, Part: String; Index, Dot, Value: Integer;
begin
  Result := False;
  Packed := 0;
  Rest := Text;
  for Index := 1 to 3 do begin
    Dot := Pos('.', Rest);
    if Index < 3 then begin
      if Dot = 0 then Exit;
      Part := Copy(Rest, 1, Dot - 1);
      Rest := Copy(Rest, Dot + 1, Length(Rest));
    end else begin
      if Dot <> 0 then Exit;
      Part := Rest;
    end;
    if (Part = '') or (Length(Part) > 5) then Exit;
    Value := StrToIntDef(Part, -1);
    if (Value < 0) or (Value > 65535) or (IntToStr(Value) <> Part) then Exit;
    Packed := Packed * 65536 + Value;
  end;
  Result := True;
end;

function RegisteredValue(Name: String): String;
begin
  Result := '';
  if not RegQueryStringValue(HKA, '{#UninstallKey}', Name, Result) then Result := '';
end;

procedure Refuse(Message: String);
begin
  Log(Message);
  if not WizardSilent then MsgBox(Message, mbError, MB_OK);
end;

function InitializeSetup: Boolean;
var Installed: String; Mine, Theirs: Int64;
begin
  Result := True;
  if not PackVersion('{#AppVersion}', Mine) then begin
    Refuse('本安装程序的版本号 {#AppVersion} 无效。');
    Result := False;
    Exit;
  end;
  Installed := RegisteredValue('DisplayVersion');
  if (Installed <> '') and PackVersion(Installed, Theirs) and (Theirs > Mine) then begin
    Refuse('已安装更新的数艺工坊 ' + Installed + '，本安装程序是 {#AppVersion}，不会降级。' +
      '如需改用旧版本，请先在“已安装的应用”中卸载数艺工坊。');
    Result := False;
  end;
end;

function SamePath(A, B: String): Boolean;
begin
  Result := CompareText(RemoveBackslashUnlessRoot(A), RemoveBackslashUnlessRoot(B)) = 0;
end;

function EmptyFolder(Directory: String): Boolean;
var Found: TFindRec;
begin
  Result := True;
  if FindFirst(AddBackslash(Directory) + '*', Found) then begin
    try
      repeat
        if (Found.Name <> '.') and (Found.Name <> '..') then begin
          Result := False;
          Exit;
        end;
      until not FindNext(Found);
    finally
      FindClose(Found);
    end;
  end;
end;

{ The chosen folder is new, empty, or this product's registered installation. Anything else may
  hold someone else's files, which [InstallDelete] and an uninstall must never touch. }
function FolderProblem(Directory: String): String;
begin
  Result := '';
  if not DirExists(Directory) or EmptyFolder(Directory) then Exit;
  if SamePath(RegisteredValue('InstallLocation'), Directory) and
     FileExists(AddBackslash(Directory) + '{#ProgramFile}') then Exit;
  Result := '文件夹 ' + Directory + ' 中已有其他文件。请选择一个空文件夹，或数艺工坊原来的安装位置。';
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var Problem: String;
begin
  Result := True;
  if CurPageID = wpSelectDir then begin
    Problem := FolderProblem(WizardDirValue);
    if Problem <> '' then begin
      Refuse(Problem);
      Result := False;
    end;
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  { Silent installs skip the folder page: check again before anything is written. }
  Result := FolderProblem(ExpandConstant('{app}'));
  if Result <> '' then Log(Result);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
    Log('Uninstalled. Settings and caches in the user profile (.datacraft) are kept.');
end;
