@echo off
rem Launcher for Alchemy on Windows. Installed as "Alchemy.bat" at the root of
rem the distribution, next to alchemy.jar, lib\ and modules\.
rem
rem Alchemy resolves .\modules, .\shapes and .\log relative to the *working
rem directory*, so change into the installation directory first. Double clicking
rem alchemy.jar works for the same reason.
rem
rem Environment:
rem   JAVA_HOME      used when set, otherwise java from PATH is used
rem   ALCHEMY_OPTS   extra JVM options, e.g. set ALCHEMY_OPTS=-Xmx1g
rem
rem Copyright (c) 2007-2010 Karl D.D. Willis. Licensed under the GPLv3 - see
rem LICENSE.md.

setlocal
set "APPDIR=%~dp0"
cd /d "%APPDIR%" || exit /b 1

set "JAVACMD=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVACMD=%JAVA_HOME%\bin\java"

rem JPen loads its JNI library from an unnamed module, which JDK 18+ warns about.
rem JDK 17 rejects the option outright, so probe rather than parse a version.
set "NATIVE_ACCESS="
"%JAVACMD%" --enable-native-access=ALL-UNNAMED -version >nul 2>&1 && set "NATIVE_ACCESS=--enable-native-access=ALL-UNNAMED"

"%JAVACMD%" -Xmx512m %NATIVE_ACCESS% %ALCHEMY_OPTS% -Djava.library.path="%APPDIR%lib\jpen" -jar "%APPDIR%alchemy.jar" %*
endlocal
