@echo off
rem Chay lai duong ong: dataset -> index -> embed -> boc tach -> do thi -> ver1x -> so lieu bao cao (bo qua buoc da co dau ra).
rem Usage: pipeline.bat [-Force] [-From N]     (xem scripts\pipeline.ps1)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pipeline.ps1" %*
