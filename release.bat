@echo off
setlocal EnableExtensions
chcp 65001 >nul

set "ROOT_DIR=%~dp0"
cd /d "%ROOT_DIR%"

set "JAVA_HOME=J:\java_packages\jdk-17.0.7"
set "ANDROID_SDK_ROOT=J:\0a-buildtools\android_sdk"
set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
set "GRADLE_USER_HOME=%ROOT_DIR%.gradle-home"
set "ANDROID_USER_HOME=%ROOT_DIR%.android-home"
set "USER_HOME_DIR=%ROOT_DIR%.user-home"
set "GRADLE_OPTS=-Duser.home=%USER_HOME_DIR% -Dfile.encoding=UTF-8"
set "OUT_DIR=%ROOT_DIR%release-out"
set "BUILD_TOOLS_VERSION=35.0.0"
set "BUILD_TOOLS_DIR=%ANDROID_SDK_ROOT%\build-tools\%BUILD_TOOLS_VERSION%"
set "ZIPALIGN_EXE=%BUILD_TOOLS_DIR%\zipalign.exe"
set "APKSIGNER_EXE=%BUILD_TOOLS_DIR%\apksigner.bat"
set "KEYSTORE_PATH=D:\apk-resign-project\buildtools\keystore.jks"
set "KEY_ALIAS=key0"
set "KEY_PASS=000000"
set "DO_ZIPALIGN=0"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JAVA_HOME is invalid: "%JAVA_HOME%"
    exit /b 1
)

if not exist "%ANDROID_SDK_ROOT%" (
    echo [ERROR] ANDROID_SDK_ROOT is invalid: "%ANDROID_SDK_ROOT%"
    exit /b 1
)

if not exist "%ZIPALIGN_EXE%" (
    echo [ERROR] zipalign not found: "%ZIPALIGN_EXE%"
    exit /b 1
)

if not exist "%APKSIGNER_EXE%" (
    echo [ERROR] apksigner not found: "%APKSIGNER_EXE%"
    exit /b 1
)

if not exist "%KEYSTORE_PATH%" (
    echo [ERROR] Keystore not found: "%KEYSTORE_PATH%"
    exit /b 1
)

if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%"
if not exist "%ANDROID_USER_HOME%" mkdir "%ANDROID_USER_HOME%"
if not exist "%USER_HOME_DIR%" mkdir "%USER_HOME_DIR%"
if not exist "%USER_HOME_DIR%\.android" mkdir "%USER_HOME_DIR%\.android"

echo sdk.dir=J:\\0a-buildtools\\android_sdk> "%ROOT_DIR%local.properties"
if errorlevel 1 (
    echo [ERROR] Failed to write local.properties
    exit /b 1
)

echo [INFO] Resetting output directory: "%OUT_DIR%"
if exist "%OUT_DIR%" (
    rd /s /q "%OUT_DIR%"
    if exist "%OUT_DIR%" (
        echo [ERROR] Failed to clean output directory: "%OUT_DIR%"
        exit /b 1
    )
)
mkdir "%OUT_DIR%"
if errorlevel 1 (
    echo [ERROR] Failed to create output directory: "%OUT_DIR%"
    exit /b 1
)

echo [INFO] Building server release...
call "%ROOT_DIR%gradlew.bat" --no-daemon :server:assembleRelease
if errorlevel 1 goto :fail

echo [INFO] Building apkviewer agent release...
call "%ROOT_DIR%gradlew.bat" --no-daemon :apkviewer:assembleRelease
if errorlevel 1 goto :fail

echo [INFO] Building app release...
call "%ROOT_DIR%gradlew.bat" --no-daemon :app:assembleRelease
if errorlevel 1 goto :fail

set "SERVER_APK="
for %%F in ("%ROOT_DIR%server\build\outputs\apk\release\*-release-unsigned.apk") do (
    set "SERVER_APK=%%~fF"
)

set "APP_APK="
for %%F in ("%ROOT_DIR%app\build\outputs\apk\release\*-release-unsigned.apk") do (
    set "APP_APK=%%~fF"
)

set "SERVER_JAR=%ROOT_DIR%app\src\main\assets\scrcpy-server.jar"
set "APKVIEWER_AGENT_JAR=%ROOT_DIR%app\src\main\assets\apkviewer-agent.jar"

if not defined SERVER_APK (
    echo [ERROR] Server APK not found.
    goto :fail
)

if not defined APP_APK (
    echo [ERROR] App APK not found.
    goto :fail
)

if not exist "%SERVER_JAR%" (
    echo [ERROR] scrcpy-server.jar not found in app assets.
    goto :fail
)

if not exist "%APKVIEWER_AGENT_JAR%" (
    echo [ERROR] apkviewer-agent.jar not found in app assets.
    goto :fail
)

copy /y "%SERVER_APK%" "%OUT_DIR%\server-release-unsigned.apk" >nul
copy /y "%APP_APK%" "%OUT_DIR%\scrcpy-release-unsigned.apk" >nul
copy /y "%SERVER_JAR%" "%OUT_DIR%\scrcpy-server.jar" >nul
copy /y "%APKVIEWER_AGENT_JAR%" "%OUT_DIR%\apkviewer-agent.jar" >nul

set "APP_UNSIGNED=%OUT_DIR%\scrcpy-release-unsigned.apk"
set "APP_ALIGNED=%OUT_DIR%\scrcpy-release-aligned.apk"
set "APP_SIGNED=%OUT_DIR%\scrcpy-release-signed.apk"
set "APK_TO_SIGN=%APP_UNSIGNED%"

if "%DO_ZIPALIGN%"=="1" (
    echo [INFO] Running zipalign...
    cmd /c ""%ZIPALIGN_EXE%" -f -v -p 4 "%APP_UNSIGNED%" "%APP_ALIGNED%""
    if errorlevel 1 goto :fail
    set "APK_TO_SIGN=%APP_ALIGNED%"
) else (
    echo [INFO] Skip zipalign: AGP release output is typically already aligned.
)

echo [INFO] Signing app release...
cmd /c ""%APKSIGNER_EXE%" sign --ks "%KEYSTORE_PATH%" --ks-key-alias %KEY_ALIAS% --ks-pass pass:%KEY_PASS% --out "%APP_SIGNED%" "%APK_TO_SIGN%""
if errorlevel 1 goto :fail

echo [OK] Build complete. Artifacts:
echo      %OUT_DIR%\server-release-unsigned.apk
echo      %OUT_DIR%\scrcpy-release-unsigned.apk
echo      %OUT_DIR%\scrcpy-server.jar
echo      %OUT_DIR%\apkviewer-agent.jar
echo      %OUT_DIR%\scrcpy-release-signed.apk
if "%DO_ZIPALIGN%"=="1" echo      %OUT_DIR%\scrcpy-release-aligned.apk
exit /b 0

:fail
echo [ERROR] Build failed.
exit /b 1
