# Android — сборка APK

## Хочешь готовый — иди в Releases

Если ты просто пользователь — **не строй сам**. Скачай APK из
[github.com/jetmil/ai-mood-pet/releases](https://github.com/jetmil/ai-mood-pet/releases),
ставь на устройство, в Settings введи WS URL + AUTH_TOKEN своего сервера.

Чтобы получить хоть один release — нужно чтобы maintainer (или ты в форке)
запушил тег вида `v0.1.0`, GitHub Actions сам соберёт и приложит APK.

## Хочешь собрать сам

### Требования
- JDK 17 (OpenJDK / Temurin)
- Android SDK API 35 (Platform-Tools + Build-Tools)
- `gradle 8.11.1+` (или дай gradlew сгенерировать wrapper)
- 4GB+ свободного места под Gradle cache + SDK

### Сборка

```bash
git clone https://github.com/jetmil/ai-mood-pet
cd ai-mood-pet/android

# POSIX wrapper не закоммичен (только gradlew.bat) — генерируем при первом запуске:
gradle wrapper --gradle-version 8.11.1
chmod +x gradlew

# Указать локальный SDK (если нет ANDROID_HOME env-var):
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Debug build (быстрее, не подписан, инсталлируется для тестов):
./gradlew assembleDebug

# APK здесь:
ls app/build/outputs/apk/debug/app-debug.apk
```

### Скачать ML-модели

В `android/app/src/main/assets/` лежат только `.gitkeep`. Перед первым
запуском приложения — скачай модели (~100 MB суммарно):

```bash
cd app/src/main/assets/models/vision/
curl -L -O https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
curl -L -O https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task

# Vosk small russian (88 MB):
cd ../../
curl -L -O https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip
unzip vosk-model-small-ru-0.22.zip
mv vosk-model-small-ru-0.22 vosk-model-ru
rm vosk-model-small-ru-0.22.zip
```

Объектный детектор (опционально, для повышения "осведомлённости" о вещах
в кадре):
```bash
cd models/vision/
curl -L -O https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/latest/efficientdet_lite0.tflite
mv efficientdet_lite0.tflite ../../efficientdet_lite0.tflite
```

### Установка на устройство

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Открыть приложение → автоматически откроется **Setup screen** → вписать:
- **WS URL** — `wss://your-domain.tld/ws/dialog` (от твоего сервера)
- **Auth Token** — тот же `AUTH_TOKEN` из `server/.env`
- **Owner name** — как тамагочи будет обращаться к тебе

## Подписанный release-build (опционально)

Debug-APK для personal-use хватает. Для дистрибуции через свой канал или
PlayStore — нужна подпись release-key.

### Один раз: сгенерировать keystore

```bash
keytool -genkey -v -keystore ai-mood-pet.keystore \
        -alias aimoodpet -keyalg RSA -keysize 2048 -validity 10000
```

Это файл `ai-mood-pet.keystore` — **не комитить, не терять**. Если потеряешь
— Android не примет апдейты от этого APK (нужен будет новый applicationId).

### Локально подписать

```bash
# в android/app/build.gradle.kts добавить signingConfig:
android {
  signingConfigs {
    create("release") {
      storeFile = file(System.getenv("KEYSTORE_FILE") ?: "ai-mood-pet.keystore")
      storePassword = System.getenv("KEYSTORE_PASSWORD")
      keyAlias = System.getenv("KEY_ALIAS") ?: "aimoodpet"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
  }
  buildTypes {
    release {
      signingConfig = signingConfigs.getByName("release")
      isMinifyEnabled = false
    }
  }
}

# затем:
KEYSTORE_PASSWORD=... KEY_PASSWORD=... ./gradlew assembleRelease
```

### Через GitHub Actions (signed)

В `.github/workflows/release-apk.yml` поменять `assembleDebug` →
`assembleRelease` + добавить шаг decode'а keystore из Repository Secrets:

```yaml
- name: Decode keystore
  run: echo "$KEYSTORE_BASE64" | base64 -d > android/ai-mood-pet.keystore
  env:
    KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}

- name: Build release APK
  working-directory: android
  env:
    KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
    KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
    KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
  run: ./gradlew assembleRelease
```

Repository Secrets создаются в Settings → Secrets → Actions:
- `KEYSTORE_BASE64`: `base64 -w 0 ai-mood-pet.keystore`
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

## Troubleshooting

| Симптом | Лечение |
|---|---|
| `SDK location not found` | `echo "sdk.dir=$ANDROID_HOME" > local.properties` |
| Permissions denied on `gradlew` | `chmod +x gradlew` (Linux/Mac) |
| `Could not resolve com.google.mediapipe:...` | Очисти `~/.gradle/caches/` и `./gradlew clean assembleDebug` |
| App crash на старте: `models not found` | Скачать ML-blobs в `assets/` (см. выше) |
| App в Setup сразу выходит | WS URL невалидный — должен начинаться `ws://` или `wss://` |
| `UninstallFailed: INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Старая версия подписана другим ключом → `adb uninstall io.github.jetmil.aimoodpet` |
