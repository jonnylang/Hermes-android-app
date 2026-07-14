# Hermes Voice — Android голосовой ассистент

Голосовой ассистент для Android, работающий с **Hermes Agent API Server**.
Без облачных сервисов, без прослоек — прямое подключение к вашему Hermes.

## Возможности

- 🎤 **Wake word** (офлайн, Vosk) — например «Эй Гермес»
- 🗣️ **STT** — распознавание речи (Vosk, офлайн)
- 🤖 **Hermes Agent** — обработка запросов через API Server
- 🔊 **TTS** — озвучка ответов (Android TextToSpeech)
- 🔄 **Streaming** — озвучка по предложениям, не дожидаясь полного ответа
- 📱 **Foreground Service** — работает в фоне, всегда слушает

## Архитектура

```
📱 Hermes Voice (Android)
   ↓ wake word «Эй Гермес» (Vosk, офлайн)
   ↓ запись речи → STT (Vosk)
   ↓ текст → POST /v1/chat/completions (streaming SSE)
🖥️ Hermes API Server (порт 8642)
   ↓ streaming ответ → TTS по предложениям
🔊 голос
```

## Требования

- Android 8.0+ (API 26)
- Hermes API Server включён и доступен по сети
- Vosk-модель в assets (см. ниже)

## Сборка

### 1. Клонировать проект

```bash
git clone <repo-url> hermes-voice
cd hermes-voice
```

### 2. Скачать Vosk-модель

Модель нужно положить в `app/src/main/assets/models/`:

**Русская модель (рекомендуется):**
```bash
cd app/src/main/assets/models/
wget https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip
unzip vosk-model-small-ru-0.22.zip
rm vosk-model-small-ru-0.22.zip
```

**Английская модель (опционально):**
```bash
wget https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
unzip vosk-model-small-en-us-0.15.zip
rm vosk-model-small-en-us-0.15.zip
```

### 3. Открыть в Android Studio

1. Android Studio → Open → выбрать папку `hermes-voice`
2. Дождаться Gradle sync
3. Нажать Run ▶

### Или собрать APK из командной строки:

```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Для debug-сборки:
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Настройка

При первом запуске:

1. Открыть приложение → ⚙ Настройки
2. Указать:
   - **URL сервера**: `http://<ip>:8642` (например `http://10.8.1.1:8642`)
   - **API ключ**: Bearer Token из `~/.hermes/.env` (`API_SERVER_KEY`)
   - **Wake word**: «эй гермес» (или своя фраза)
   - **Язык**: Русский / English
3. Сохранить
4. Нажать **Запустить**
5. Сказать «Эй Гермес» → задать вопрос → получить ответ голосом

## Сеть

Телефон должен иметь доступ к IP-адресу сервера. Варианты:

| Способ | URL |
|--------|-----|
| AmneziaWG / WireGuard | `http://10.8.1.1:8642` |
| Tailscale | `http://<tailscale-ip>:8642` |
| Локальный WiFi | `http://192.168.x.x:8642` |
| ngrok (тест) | `https://<id>.ngrok.io` |

## Структура проекта

```
hermes-voice/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/models/          ← Vosk модели
│       ├── res/values/
│       │   ├── strings.xml
│       │   └── themes.xml
│       └── java/com/nous/hermesvoice/
│           ├── HermesVoiceApp.kt       ← Application, notification channel
│           ├── data/
│           │   └── SettingsRepository.kt  ← DataStore (URL, ключ, wake word)
│           ├── vosk/
│           │   └── VoskManager.kt        ← Vosk: wake word + STT
│           ├── net/
│           │   └── HermesClient.kt       ← HTTP к Hermes API (streaming)
│           ├── tts/
│           │   └── TtsManager.kt         ← Android TTS, streaming-озвучка
│           ├── service/
│           │   └── VoiceService.kt       ← Foreground service, state machine
│           └── ui/
│               └── MainActivity.kt       ← Compose UI (настройки + статус)
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/wrapper/
```

## State Machine

```
IDLE (wake word mode)
  │  wake word detected
  ▼
LISTENING (free-form STT)
  │  final result
  ▼
THINKING → SPEAKING (streaming TTS)
  │  TTS done
  ▼
IDLE (wake word mode)
```

## Лицензия

MIT