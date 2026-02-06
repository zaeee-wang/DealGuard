@echo off
REM Apply Android NDK CMake fix to java-llama.cpp submodule.
REM Run from anywhere. Script dir = scripts/, repo root = parent of scripts/.
cd /d "%~dp0"
cd ..
if errorlevel 1 (
  echo Error: Cannot resolve repo root from: %~dp0
  exit /b 1
)
set "ROOT=%CD%"
set "SUBMODULE=%ROOT%\app\java-llama.cpp"
set "PATCH=%ROOT%\patches\java-llama-android-cmake.patch"

if not exist "%PATCH%" (
  echo Patch not found: %PATCH%
  exit /b 1
)
if not exist "%SUBMODULE%\CMakeLists.txt" (
  echo Submodule not initialized. Run: git submodule update --init
  echo Expected: %SUBMODULE%
  exit /b 1
)

cd /d "%SUBMODULE%"
if errorlevel 1 (
  echo Error: Cannot change to submodule: %SUBMODULE%
  exit /b 1
)

git apply --check "%PATCH%" 2>nul
if %ERRORLEVEL% equ 0 (
  git apply "%PATCH%"
  echo Applied java-llama-android-cmake.patch
) else (
  echo Patch already applied or not applicable.
)
exit /b 0
