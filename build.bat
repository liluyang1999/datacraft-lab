@echo off
setlocal DisableDelayedExpansion
rem Builds the Windows installer dist\Setup.exe of the desktop console (docs/desktop/README.md).
rem CI's build stage runs this same entry point; the version comes from the root pom.xml revision.
set "PYTHON=python"
where python >nul 2>nul || set "PYTHON=py -3"
%PYTHON% -B "%~dp0cicd\desktop\release.py" build %*
set "status=%errorlevel%"
rem Opened by a double-click: keep the window until the installer path or the failure was read.
if not defined CI call :pause_if_double_clicked
exit /b %status%

:pause_if_double_clicked
echo %cmdcmdline% | findstr /i /c:"%~nx0" >nul && pause
exit /b 0
