set JAVA_HOME="C:\tools\java\jdk-21.0.1"

REM Dont forget to update version in README
set new_version=1.0.22

set jsh_content=^
    Files.writeString(Path.of("README.md"), ^
        Files.readString(Path.of("README.md")).replaceAll(^
            "<brinvex-dms.version>(.*)</brinvex-dms.version>", ^
            "<brinvex-dms.version>%%s</brinvex-dms.version>".formatted(System.getenv("new_version"))), ^
    StandardOpenOption.TRUNCATE_EXISTING);

echo %jsh_content% | %JAVA_HOME%\bin\jshell -


call mvnw clean package
call mvnw versions:set -DnewVersion=%new_version%
call mvnw versions:commit
call mvnw clean deploy -DskipTests

REM Commit and push Release info