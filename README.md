# COMP3011 Assignment 2 - Java Local Project

For more information on this code and how to get started, see https://learn.adelaide.edu.au/courses/30747/assignments/94505

## Run the subtitle server in Eclipse

1. Open **File → Import → Maven → Existing Maven Projects**. Select the
   repository's `subtitle-server` folder and import it.
2. Open **Run → Run Configurations → Maven Build** and create a configuration.
   Set **Base directory** to the `subtitle-server` folder and select JDK 25 on
   the **JRE** tab.
3. For real subtitles, enter `spring-boot:run` in **Goals**. On the
   **Environment** tab, add `OPENAI_API_KEY` with your OpenAI API key as its
   value.
4. Click **Run** and wait for the Eclipse Console to report that Tomcat started
   on port 8080. Stop the active server before switching between Whisper and stub
   mode.

## Play a clip with subtitles

Run VideoPlayer from Eclipse. At the `Enter options:` prompt, enter the
following with the path to a video that has an audio track:

```text
-S -a "C:\path\to\clip.mp4"
```

Press **S** or click the subtitle button next to mute to toggle captions.
If the opening dialogue passes while the server is transcribing, press **Home**
to replay the clip after subtitles arrive.

Check the total transcription usage in seconds since the server started:

```powershell
curl.exe -f http://localhost:8080/api/v1/global/usage
```

## Test with sample subtitles

To run without an API key, set **Goals** in the Eclipse server configuration to
`spring-boot:run -Dspring-boot.run.profiles=stub`.

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
