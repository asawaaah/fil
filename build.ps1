# Runs Gradle with the JDK bundled in Android Studio.
# Usage: .\build.ps1 assembleDebug   |   .\build.ps1 test
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& "$PSScriptRoot\gradlew.bat" @args
exit $LASTEXITCODE
