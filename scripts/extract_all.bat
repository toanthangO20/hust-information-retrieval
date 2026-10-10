@echo off
rem Chay toan bo duong ong buoc 3: bocs tach -> do thi lien ket -> chi muc thu nghiem.
rem Dieu kien: data\raw da co (scripts\extract.bat snapshot). Nap MongoDB: scripts\extract.bat load (can MongoDB dang chay).
setlocal
cd /d "%~dp0.."
call scripts\extract.bat run --out data\extract\v1 --threads 8
if errorlevel 1 exit /b 1
call scripts\extract.bat graph --out data\extract\v1
if errorlevel 1 exit /b 1
call scripts\extract.bat index --out data\extract\v1 --index index\ver1x
