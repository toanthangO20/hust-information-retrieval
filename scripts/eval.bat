@echo off
rem Usage: eval.bat embed^|run^|pool^|qrels^|eval [--tuy-chon gia-tri]  (xem eval\README.md)
setlocal
chcp 65001 >nul
cd /d "%~dp0.."
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=C:\Program Files\Microsoft\jdk-11.0.16.101-hotspot"
"%JAVA_HOME%\bin\java" -Dfile.encoding=UTF-8 -Dorg.slf4j.simpleLogger.defaultLogLevel=error -Xmx4g -cp "target\hust-crawler-1.0.jar;target\lib\*" vn.hust.ir.eval.EvalMain %*
