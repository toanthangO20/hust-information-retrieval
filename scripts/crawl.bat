@echo off
rem Usage: crawl.bat crawl^|update^|export^|stats [options]
setlocal
cd /d "%~dp0.."
rem Java 11+ required. Fall back to a known JDK when JAVA_HOME is unset or invalid.
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=C:\Program Files\Microsoft\jdk-11.0.16.101-hotspot"
"%JAVA_HOME%\bin\java" -Dfile.encoding=UTF-8 -Dorg.slf4j.simpleLogger.defaultLogLevel=error -Xmx2g -cp "target\hust-crawler-1.0.jar;target\lib\*" vn.hust.ir.crawler.Main %*
