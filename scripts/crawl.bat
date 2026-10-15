@echo off
rem Usage: crawl.bat crawl^|update^|export^|stats^|inspect [options]   (xem README.md)
setlocal
call "%~dp0_env.bat" || exit /b 1
"%JAVA_HOME%\bin\java" -Dfile.encoding=UTF-8 -Dorg.slf4j.simpleLogger.defaultLogLevel=error -Xmx2g -cp "%HUST_CP%" vn.hust.ir.crawler.Main %*
