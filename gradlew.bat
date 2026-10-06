@if "%DEBUG%" == "" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows :batch
@rem ===
@rem Use the maximum available, or set MAX_FD != -1 to use that value.
@rem ===
set MAX_FD=maximum

@rem >>> IMPORTANT: Change this to match the actual location of your Java installation >>>
set JAVA_HOME=C:\Program Files\Java\jdk1.8.0_291
@rem <<< IMPORTANT: Change this to match the actual location of your Java installation <<<

@rem Determine the Java command to use to start the JVM.
if "%JAVA_HOME%" == "" goto :noJavaHome
set JAVACMD=%JAVA_HOME%\bin\java.exe
goto :checkJava
:noJavaHome
set JAVACmd=java.exe
:checkJava
if not exist "%JAVACmd%" goto :noJava
goto :checkExecPermissions
:noJava
echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.
echo.
goto :end

:checkExecPermissions
@rem Get command-line arguments, handling Windows variants
if not "%OS%" == "Windows_NT" goto :win9xME_args

:win9xME_args
@rem Slurp the command line arguments.
set CMD_LINE_ARGS=
set _SKIP=2

:win9xME_args_slurp
if "x%~1" == "x" goto :win9xME_args_done
set CMD_LINE_ARGS=%*
goto :win9xME_args_done

:win9xME_args_done
@rem Setup the command line
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

@rem Execute Gradle
"%JAVACMD%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %CMD_LINE_ARGS%

:end
@rem End of script
