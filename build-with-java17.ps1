$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.3.7-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug
