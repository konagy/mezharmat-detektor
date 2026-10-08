@echo off
setlocal
cd /d "%~dp0"
if not exist "v2\target\classes\org\konagy\honeydew\App.class" (
  echo Run setup and build first - see v2\README.md.
  pause
  exit /b 1
)
".tools\jdk\jdk-21.0.12.1+1\bin\java.exe" -Xmx1024m -Dorg.bytedeco.javacpp.cachedir=".tools\native-cache" -cp "v2\target\classes;v2\target\lib\*;v2\vendor\*" org.konagy.honeydew.App %*
if errorlevel 1 pause
