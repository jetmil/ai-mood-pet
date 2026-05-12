# Runtime assets

This directory holds **third-party model files and your own voice bank**.
None of them are committed to the public repo — you must drop them in
yourself before building the app, or the app will crash at first use.

## What goes where

```
assets/
├── efficientdet_lite0.tflite          # MediaPipe object detector
├── models/
│   └── vision/
│       ├── face_detector_short_range.tflite   # MediaPipe face detector
│       ├── face_landmarker.task               # MediaPipe face landmarks
│       └── hand_landmarker.task               # MediaPipe hand landmarks
├── voice/
│   ├── baby_robot/    # one folder per VoiceStyle key in Settings.kt
│   ├── bass_grumpy/
│   ├── sage/
│   ├── flatterer/
│   ├── mowgli/
│   └── statham/
└── vosk-model-ru/     # Vosk small Russian model (or any other language)
```

## Where to get them

### MediaPipe models (Apache 2.0)

- `efficientdet_lite0.tflite` — https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/latest/efficientdet_lite0.tflite
- `face_detector_short_range.tflite` — https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/latest/face_detector_short_range.tflite
- `face_landmarker.task` — https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task
- `hand_landmarker.task` — https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task

Official catalog: https://ai.google.dev/edge/mediapipe/solutions/guide

### Vosk speech-recognition model

Download a small model from https://alphacephei.com/vosk/models and
unpack it into `assets/vosk-model-ru/` so the `am/`, `graph/`, `conf/`,
`ivector/` subfolders sit directly under that path. The default keyword
list (`io.github.jetmil.aimoodpet.EyesViewModel#wakeWord`) targets
Russian — change both the model and the keyword list for other
languages.

### Voice bank

Each subfolder of `voice/` corresponds to one `VoiceStyle` key in
[`settings/Settings.kt`](../java/io/github/jetmil/aimoodpet/settings/Settings.kt).
A voice folder is a flat list of files plus a manifest:

```
voice/bass_grumpy/
├── manifest.tsv            # tab-separated:  id<TAB>tag<TAB>text
├── anger_01.ogg
├── anger_01.env.json       # optional: per-file amplitude envelope
├── greeting_01.ogg
├── greeting_01.env.json
└── ...
```

The `tag` column is what the engine looks up — see
[`voice/VoiceTriggerEngine.kt`](../java/io/github/jetmil/aimoodpet/voice/VoiceTriggerEngine.kt)
for the full vocabulary (`greeting`, `joy`, `fear`, `anger`, `surprise`,
`sadness`, `curious`, `petting`, `bye`, ...).

You can generate these clips with any TTS that fits the persona — the
project was originally built around `edge-tts` for prototyping and
local CosyVoice / Piper for higher-quality persona voices. The
`.env.json` sidecar is optional; without it the player falls back to
real-time `MediaPlayer` amplitude.
