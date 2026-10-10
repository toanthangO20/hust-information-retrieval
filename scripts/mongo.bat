@echo off
rem Chay MongoDB Community cuc bo (tools\mongodb, du lieu data\mongo, chi lang nghe 127.0.0.1:27017). Dung Ctrl+C de dung.
rem Tai ban zip: https://fastdl.mongodb.org/windows/mongodb-windows-x86_64-7.0.14.zip -> giai nen vao tools\mongodb
setlocal
cd /d "%~dp0.."
if not exist data\mongo mkdir data\mongo
"tools\mongodb\mongodb-win32-x86_64-windows-7.0.14\bin\mongod.exe" --dbpath data\mongo --bind_ip 127.0.0.1 --port 27017
