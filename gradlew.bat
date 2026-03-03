@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem

@if "%DEBUG%" == "" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Force UTF-8 for predictable console/file encoding.
chcp 65001 >NUL

@rem Project-local toolchain defaults (migrated from release.bat).
set "PROJECT_JAVA_HOME=J:\java_packages\jdk-17.0.7"
set "PROJECT_ANDROID_SDK_ROOT=J:\0a-buildtools\android_sdk"
set "PROJECT_ANDROID_NDK_VERSION=27.3.13750724"
set "PROJECT_GRADLE_USER_HOME=%APP_HOME%\.gradle-home"
set "PROJECT_ANDROID_USER_HOME=%APP_HOME%\.android-home"
set "PROJECT_USER_HOME=%APP_HOME%\.user-home"

set "JAVA_HOME=%PROJECT_JAVA_HOME%"
set "ANDROID_SDK_ROOT=%PROJECT_ANDROID_SDK_ROOT%"
set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
set "ANDROID_NDK_HOME=%ANDROID_SDK_ROOT%\ndk\%PROJECT_ANDROID_NDK_VERSION%"
set "ANDROID_NDK_ROOT=%ANDROID_NDK_HOME%"
set "NDK_HOME=%ANDROID_NDK_HOME%"
set "GRADLE_USER_HOME=%PROJECT_GRADLE_USER_HOME%"
set "ANDROID_USER_HOME=%PROJECT_ANDROID_USER_HOME%"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo.
    echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
    echo.
    echo Please update PROJECT_JAVA_HOME in gradlew.bat.
    goto fail
)

if not exist "%ANDROID_SDK_ROOT%" (
    echo.
    echo ERROR: ANDROID_SDK_ROOT is set to an invalid directory: %ANDROID_SDK_ROOT%
    echo.
    echo Please update PROJECT_ANDROID_SDK_ROOT in gradlew.bat.
    goto fail
)

if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%"
if not exist "%ANDROID_USER_HOME%" mkdir "%ANDROID_USER_HOME%"
if not exist "%PROJECT_USER_HOME%" mkdir "%PROJECT_USER_HOME%"
if not exist "%PROJECT_USER_HOME%\.android" mkdir "%PROJECT_USER_HOME%\.android"

set "GRADLE_ENV_OPTS=-Duser.home=%PROJECT_USER_HOME% -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
if defined GRADLE_OPTS (
    set "GRADLE_OPTS=%GRADLE_ENV_OPTS% %GRADLE_OPTS%"
) else (
    set "GRADLE_OPTS=%GRADLE_ENV_OPTS%"
)

if defined JAVA_TOOL_OPTIONS (
    set "JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 %JAVA_TOOL_OPTIONS%"
) else (
    set "JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
)

set "SDK_DIR_ESCAPED=%ANDROID_SDK_ROOT:\=\\%"
(
    echo sdk.dir=%SDK_DIR_ESCAPED%
)>"%APP_HOME%\local.properties"
if errorlevel 1 (
    echo.
    echo ERROR: Failed to write local.properties.
    echo.
    goto fail
)

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto init

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto init

echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.

goto fail

:init
@rem Get command-line arguments, handling Windows variants

if not "%OS%" == "Windows_NT" goto win9xME_args

:win9xME_args
@rem Slurp the command line arguments.
set CMD_LINE_ARGS=
set _SKIP=2

:win9xME_args_slurp
if "x%~1" == "x" goto execute

set CMD_LINE_ARGS=%*

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %CMD_LINE_ARGS%

:end
@rem End local scope for the variables with windows NT shell
if "%ERRORLEVEL%"=="0" goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
if  not "" == "%GRADLE_EXIT_CONSOLE%" exit 1
exit /b 1

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
