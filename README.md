# Gemini Live on Quest

An Android application that integrates with Google's Gemini Live API (free tier) to provide real-time AI interactions on Meta Quest devices.

## Requirements

- **Android Studio Narwhal 3 Feature Drop | 2025.1.3 RC 2** or later
- **Gemini Live API Key** from Google AI Studio
- **Meta Quest 3**

## Setup

### 1. API Key Configuration

1. Copy the example secrets file:
   ```bash
   cp secrets.properties.example secrets.properties
   ```

2. Open `secrets.properties` and add your Gemini Live API key:
   ```properties
   GEMINI_API_KEY=your_api_key_here
   ```

   > **Note**: You can obtain a Gemini Live API key from [Google AI Studio](https://aistudio.google.com/app/apikey)

### 2. System Prompt Customization

To modify the AI's behavior and responses, edit the system prompt in:

```
app/src/main/res/values/strings.xml
```

Look for the `prompt` string resource and customize it according to your needs:

```xml
<string name="prompt">Your custom system prompt here...</string>
```
You can also configure the `gemini_url` and `gemini_model`.

## Features

- Real-time video streaming to Gemini Live API
- Audio input and output processing

## Demo

Demo available: https://youtu.be/EO4uojTqLfo?si=Chdhc1PiGCLg78ug

This shows Gemini playing Top Trumps! The initial system prompt explains the rules and that's it!

![Screenshot](screenshot.png)

## References

Gemin Live API: https://ai.google.dev/gemini-api/docs/live

This project uses Camera access code from the [Meta Spatial Scanner](https://github.com/meta-quest/Meta-Spatial-SDK-Samples/tree/main/Showcases/meta_spatial_scanner) (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.
