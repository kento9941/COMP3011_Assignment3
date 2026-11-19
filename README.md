# COMP3011 Assignment 2 - Java Local Project

For more information on this code and how to get started, see https://learn.adelaide.edu.au/courses/30747/assignments/94505

## Subtitle server stub

With JDK 25 and Maven, run the server from the repository root:

```powershell
mvn -f subtitle-server/pom.xml spring-boot:run "-Dspring-boot.run.profiles=stub"
```

In a second terminal, upload a WAV:

```powershell
curl.exe -f -F "audio=@sample.wav" http://localhost:8080/api/v1/subtitles
```

The stub returns the same sample words for each nonempty upload. The `start`
and `end` fields are in seconds from the beginning of the uploaded audio.

With the stub running, start the player with `-S` or press `S` during playback
to request subtitles. The sample cue lasts until 1.2 seconds into the clip.
If it has already passed by the time the response arrives, press Home to
replay it.
