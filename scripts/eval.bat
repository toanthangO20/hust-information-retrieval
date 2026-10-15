@echo off
rem Usage: eval.bat embed^|run^|pool^|qrels^|eval^|bench^|dashboard [--tuy-chon gia-tri]  (xem eval\README.md)
setlocal
call "%~dp0_env.bat" || exit /b 1
"%JAVA_HOME%\bin\java" -Dfile.encoding=UTF-8 -Dorg.slf4j.simpleLogger.defaultLogLevel=error -Xmx4g -cp "%HUST_CP%" vn.hust.ir.eval.EvalMain %*
