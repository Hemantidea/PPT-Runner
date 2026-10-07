#define MyAppName "PPT Runner"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "Hemant Verma"
#define MyAppExeName "PptRunner.Desktop.exe"

[Setup]
AppId={{C6E4E0F1-3A4E-4A4D-8E6D-PPT-RUNNER-2026}}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}

DefaultDirName={autopf}\PPT Runner
DefaultGroupName=PPT Runner

OutputDir=.\output
OutputBaseFilename=PPT-Runner-Setup

Compression=lzma
SolidCompression=yes

ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

PrivilegesRequired=admin

WizardStyle=modern dynamic

SetupIconFile=..\Assets\ppt_runner_desktop_icon_full_width.ico

LicenseFile=Terms.rtf
InfoBeforeFile=Privacy.rtf

UninstallDisplayIcon={app}\{#MyAppExeName}

[Files]
Source: "..\publish\win-x64\{#MyAppExeName}"; \
    DestDir: "{app}"; \
    Flags: ignoreversion

[Icons]
Name: "{group}\PPT Runner"; \
    Filename: "{app}\{#MyAppExeName}"; \
    IconFilename: "{app}\{#MyAppExeName}"

Name: "{autodesktop}\PPT Runner"; \
    Filename: "{app}\{#MyAppExeName}"; \
    IconFilename: "{app}\{#MyAppExeName}"; \
    Tasks: desktopicon

[Tasks]
Name: "desktopicon"; \
    Description: "Create a desktop shortcut"; \
    GroupDescription: "Additional shortcuts:"