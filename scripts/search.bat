@echo off
rem Usage: search.bat dataset^|index^|serve^|query [options]
setlocal
cd /d "%~dp0.."
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=C:\Program Files\Microsoft\jdk-11.0.16.101-hotspot"
"%JAVA_HOME%\bin\java" -Dfile.encoding=UTF-8 -Dorg.slf4j.simpleLogger.defaultLogLevel=error -Xmx2g -cp "target\hust-crawler-1.0.jar;target\lib\*" vn.hust.ir.search.SearchMain %*
