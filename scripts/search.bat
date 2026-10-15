@echo off
rem Usage: search.bat dataset^|index^|serve^|query [options]   (serve: http://localhost:8080, so sanh mo hinh: /compare.html)
setlocal
call "%~dp0_env.bat" || exit /b 1
"%JAVA_HOME%\bin\java" -Dfile.encoding=UTF-8 -Dorg.slf4j.simpleLogger.defaultLogLevel=error -Xmx2g -cp "%HUST_CP%" vn.hust.ir.search.SearchMain %*
