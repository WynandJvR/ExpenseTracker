@echo off
setlocal enabledelayedexpansion

:: Set paths from your system variables
set "JAVA_HOME=C:\Users\wynan\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.7.6-hotspot"
set "MAVEN_HOME=C:\apache-maven-3.9.9"
set "JAVAFX_SDK=C:\path\to\your\javafx-sdk-21.0.2\lib"  :: Replace with your actual JavaFX SDK path!

:: Add Java and Maven to PATH temporarily
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"

:: Clean previous build in project root
echo Cleaning previous build...
if exist "dist" (
    rmdir /s /q "dist"
)

:: Verify tools
where java >nul 2>&1 || (
    echo Java not found in PATH
    pause
    exit /b 1
)

where mvn >nul 2>&1 || (
    echo Maven not found in PATH
    pause
    exit /b 1
)

:: Build with Maven
echo Running Maven build...
call mvn clean package
if %ERRORLEVEL% neq 0 (
    echo Maven build failed with error code %ERRORLEVEL%. Aborting.
    pause
    exit /b %ERRORLEVEL%
)

:: Prepare JPackage command (output to current directory's dist folder)
set "JPKG_CMD=jpackage"
set "JPKG_CMD=%JPKG_CMD% --name ExpenseTracker"
set "JPKG_CMD=%JPKG_CMD% --input target"
set "JPKG_CMD=%JPKG_CMD% --main-jar expense-tracker-1.0-SNAPSHOT.jar"
set "JPKG_CMD=%JPKG_CMD% --main-class com.wyn.expensetracker.ExpenseTrackerApp"
set "JPKG_CMD=%JPKG_CMD% --module-path "%JAVAFX_SDK%;%USERPROFILE%\.m2\repository\org\openjfx\javafx-base\21\javafx-base-21-win.jar;%USERPROFILE%\.m2\repository\org\openjfx\javafx-controls\21\javafx-controls-21-win.jar;%USERPROFILE%\.m2\repository\org\openjfx\javafx-fxml\21\javafx-fxml-21-win.jar;%USERPROFILE%\.m2\repository\org\openjfx\javafx-graphics\21\javafx-graphics-21-win.jar""
set "JPKG_CMD=%JPKG_CMD% --add-modules javafx.controls,javafx.fxml,javafx.graphics"
set "JPKG_CMD=%JPKG_CMD% --type app-image"
set "JPKG_CMD=%JPKG_CMD% --dest .\dist"

:: Run JPackage
echo Building ExpenseTracker application...
echo Command: %JPKG_CMD%
%JPKG_CMD%

if %ERRORLEVEL% equ 0 (
    echo Build completed successfully!
    echo Application created in: %CD%\dist\ExpenseTracker
    start "" "%CD%\dist\ExpenseTracker\ExpenseTracker.exe"
) else (
    echo Build failed with error code %ERRORLEVEL%. Check the output for details.
)

pause