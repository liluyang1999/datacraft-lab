@echo off
setlocal DisableDelayedExpansion
rem Starts the desktop console from the sources for development (./mvnw -f desktop/pom.xml javafx:run).
rem It uses the data folder ~/.datacraft unless DATACRAFT_DESKTOP_HOME names another one.
call "%~dp0mvnw.cmd" -B -ntp -f "%~dp0desktop\pom.xml" javafx:run
set "status=%errorlevel%"
if not "%status%"=="0" if not defined CI call :pause_if_double_clicked
exit /b %status%

:pause_if_double_clicked
echo %cmdcmdline% | findstr /i /c:"%~nx0" >nul && pause
exit /b 0
