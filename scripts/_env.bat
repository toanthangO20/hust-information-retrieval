@echo off
rem Thiet lap chung cho cac script (goi bang: call "%~dp0_env.bat" || exit /b 1):
rem UTF-8, thu muc goc du an, JAVA_HOME (Java 11+), kiem tra da build jar; dat HUST_CP = classpath.
chcp 65001 >nul
cd /d "%~dp0.."
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=C:\Program Files\Microsoft\jdk-11.0.16.101-hotspot"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [loi] Khong tim thay Java 11+. Dat bien JAVA_HOME roi chay lai.
  exit /b 1
)
if not exist target\hust-crawler-1.0.jar (
  echo [loi] Chua co target\hust-crawler-1.0.jar. Build truoc: tools\apache-maven-3.9.9\bin\mvn package
  exit /b 1
)
set "HUST_CP=target\hust-crawler-1.0.jar;target\lib\*"
exit /b 0
