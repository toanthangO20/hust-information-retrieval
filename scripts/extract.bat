@echo off
rem Usage: extract.bat snapshot^|run^|graph^|index^|load^|eval^|eval-meta^|strips^|show^|mongo-demo [options]   (xem README.md, phan "Buoc 3")
setlocal
call "%~dp0_env.bat" || exit /b 1
"%JAVA_HOME%\bin\java" -Dfile.encoding=UTF-8 -Dorg.slf4j.simpleLogger.defaultLogLevel=error -Xmx3g -cp "%HUST_CP%" vn.hust.ir.extract.ExtractMain %*
