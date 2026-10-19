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
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Process-local F defaults; validation happens before the Gradle JVM starts.
set "PIXEL_PLACE_WORK_ROOT=F:/pixel-place-work/projects/pixel-place"
if defined PIXEL_PLACE_WORK_SESSION set "PIXEL_PLACE_WORK_ROOT=F:/pixel-place-work/sessions/%PIXEL_PLACE_WORK_SESSION%"
set PIXEL_PLACE_CACHE_ARGUMENT=--project-cache-dir "%PIXEL_PLACE_WORK_ROOT%/project-cache"
set "PIXEL_PLACE_REQUESTED_CACHE="
:pixelPlaceScanArguments
if "%~1"=="" goto pixelPlaceArgumentsReady
if "%~1"=="--project-cache-dir" (
    set "PIXEL_PLACE_CACHE_ARGUMENT="
    set "PIXEL_PLACE_REQUESTED_CACHE=%~2"
)
set "PIXEL_PLACE_ONE_ARGUMENT=%~1"
if "%PIXEL_PLACE_ONE_ARGUMENT:~0,20%"=="--project-cache-dir=" (
    set "PIXEL_PLACE_CACHE_ARGUMENT="
    set "PIXEL_PLACE_REQUESTED_CACHE=%PIXEL_PLACE_ONE_ARGUMENT:~20%"
)
shift
goto pixelPlaceScanArguments
:pixelPlaceArgumentsReady
set "GRADLE_USER_HOME=F:/pixel-place-work/gradle-home"
set "TEMP=%PIXEL_PLACE_WORK_ROOT%/temp"
set "TMP=%TEMP%"
set "GRAPHIFY_OUT=F:/pixel-place-work/projects/pixel-place/graphify-out"
set "PYTHONDONTWRITEBYTECODE=1"
if not defined JAVA_HOME set "JAVA_HOME=C:/Program Files/Java/jdk-21.0.10"
powershell.exe -NoProfile -File "%APP_HOME%\scripts\prepare-gradle-work.ps1"
if errorlevel 1 goto fail
set GRADLE_OPTS=%GRADLE_OPTS% "-Djava.io.tmpdir=%TEMP%" "-Djna.tmpdir=%PIXEL_PLACE_WORK_ROOT%/jna"

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:execute
@rem Setup the command line



@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" "-Dorg.gradle.jvmargs=-Xmx512m -Djava.io.tmpdir=%TEMP% -Djna.tmpdir=%PIXEL_PLACE_WORK_ROOT%/jna" -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %PIXEL_PLACE_CACHE_ARGUMENT% %*

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%GRADLE_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
