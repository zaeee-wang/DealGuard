@echo off
REM Apply Android NDK CMake fix to java-llama.cpp submodule.
REM Run from repo root after: git submodule update --init
set ROOT=%~dp0..
cd /d "%ROOT%"
set SUBMODULE=%ROOT%\app\java-llama.cpp
set PATCH=%ROOT%\patches\java-llama-android-cmake.patch
if not exist "%PATCH%" (
  echo Patch not found: %PATCH%
  exit /b 1
)
if not exist "%SUBMODULE%\CMakeLists.txt" (
  echo Submodule not initialized. Run: git submodule update --init
  exit /b 1
)
cd /d "%SUBMODULE%"
git apply --check "%PATCH%" 2>nul
if %ERRORLEVEL% equ 0 (
  git apply "%PATCH%"
  echo Applied java-llama-android-cmake.patch
) else (
  echo Patch already applied or not applicable.
)
exit /b 0
