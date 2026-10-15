@echo off
rem Sinh lai so lieu bao cao truc quan tu dau ra cua cac buoc -> docs\data\results.js (+ results.json), roi mo slide.
rem Usage: report.bat [--no-open]
rem   - tinh lai do do, kiem dinh (hoan vi + Holm + bootstrap) tu eval\runs + eval\qrels; do thi tu data\extract\v1 (neu co)
rem   - nguon nao vang mat thi giu so lieu cua lan sinh truoc
setlocal
call "%~dp0_env.bat" || exit /b 1
"%JAVA_HOME%\bin\java" -Dfile.encoding=UTF-8 -Dorg.slf4j.simpleLogger.defaultLogLevel=error -Xmx3g -cp "%HUST_CP%" vn.hust.ir.eval.EvalMain dashboard
if errorlevel 1 exit /b 1
if /i not "%~1"=="--no-open" start "" "docs\BAO_CAO_TRUC_QUAN.html"
