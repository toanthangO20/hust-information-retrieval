@echo off
rem Usage: extract.bat snapshot^|blocks^|run^|load [options]   (xem README.md, phan "Buoc 3")
setlocal
cd /d "%~dp0.."
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=C:\Program Files\Microsoft\jdk-11.0.16.101-hotspot"
"%JAVA_HOME%\bin\java" -Dfile.encoding=UTF-8 -Dorg.slf4j.simpleLogger.defaultLogLevel=error -Xmx3g -cp "target\hust-crawler-1.0.jar;target\lib\*" vn.hust.ir.extract.ExtractMain %*
