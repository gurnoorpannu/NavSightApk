# Gemini AI Integration for Blind Assistance

This app now includes Google Gemini Flash API integration to provide intelligent scene descriptions for blind users.

## Features

- **Real-time Object Detection**: Uses TensorFlow Lite to detect objects in the camera view
- **AI Scene Understanding**: Gemini Flash analyzes the scene and provides contextual descriptions
- **Text-to-Speech**: Automatically speaks scene descriptions to assist blind users
- **Position Awareness**: Describes object positions (left, center, right) and distances
- **Safety Focus**: Prioritizes obstacles and hazards in descriptions

## Setup Instructions

### 1. Get a Free Gemini API Key

1. Visit [Google AI Studio](https://makersuite.google.com/app/apikey)
2. Sign in with your Google account
3. Click "Create API Key"
4. Copy your API key

### 2. Add API Key to the App

Open `app/src/main/res/values/api_keys.xml` and replace `YOUR_GEMINI_API_KEY_HERE` with your actual API key:

```xml
<string name="gemini_api_key">YOUR_ACTUAL_API_KEY_HERE</string>
```

### 3. Build and Run

```bash
./gradlew assembleDebug
```

Or use Android Studio to build and run the app.

## How It Works

1. **Camera captures frames** continuously
2. **TensorFlow Lite detects objects** in each frame with bounding boxes
3. **Every 5 seconds**, the app sends the current frame to Gemini Flash API
4. **Gemini analyzes** the scene considering:
   - Detected objects and their positions
   - Potential obstacles or hazards
   - General environment context
5. **Text-to-Speech announces** the description to the user

## Usage Tips

- Point the camera in the direction you want to explore
- The app will automatically announce what's ahead every 5 seconds
- Listen for position cues: "Ahead", "Left", "Right"
- The app prioritizes safety-relevant information

## Customization

### Adjust Announcement Frequency

In `GeminiSceneHelper.kt`, modify:
```kotlin
private val announcementInterval = 5000L // Change to desired milliseconds
```

### Modify Prompt

Edit the `buildPrompt()` function in `GeminiSceneHelper.kt` to customize what information Gemini focuses on.

### Change Voice Settings

In `GeminiSceneHelper.kt`, you can adjust TTS settings:
```kotlin
textToSpeech?.setSpeechRate(1.0f) // Speed
textToSpeech?.setPitch(1.0f) // Pitch
```

## API Costs

- Gemini 1.5 Flash is **free** for up to 15 requests per minute
- This app sends ~12 requests per minute (every 5 seconds)
- Perfect for personal use and testing

## Privacy

- Images are sent to Google's Gemini API for analysis
- No images are stored by this app
- Review [Google's Privacy Policy](https://policies.google.com/privacy) for API usage

## Troubleshooting

### "Text-to-Speech not ready"
- Make sure your device has TTS data installed
- Go to Settings > Accessibility > Text-to-Speech

### "Unable to analyze scene"
- Check your internet connection
- Verify your API key is correct
- Check API quota limits

### No audio output
- Check device volume
- Ensure TTS is enabled in accessibility settings
- Test TTS in device settings

## Future Enhancements

- Distance estimation using depth sensors
- Haptic feedback for obstacles
- Offline mode with cached descriptions
- Multi-language support
- Custom voice profiles
