Aigentik
Your personal AI agent — running entirely on your Android device.
Aigentik is a fully offline, on-device AI assistant for Android that monitors your SMS, Google Voice, and Gmail — replying intelligently on your behalf using a locally hosted language model. No cloud. No subscriptions. Your data never leaves your phone.
✨ Features
Feature
Description
🤖 On-Device AI
Powered by llama.cpp with Qwen3 GGUF models — fully offline inference
💬 SMS Auto-Reply
Monitors and replies to direct SMS messages via SmsManager
📞 Google Voice
Detects GVoice forwarded emails and replies through the correct thread
📧 Gmail Monitoring
IMAP IDLE push notifications — instant email detection, no polling delay
👥 Contact-Aware
Syncs your Android contacts and applies per-contact reply rules
🔀 Smart Routing
Automatically routes replies to the correct channel (SMS, GVoice, or Email)
📡 Channel Control
Toggle SMS, Google Voice, and Email monitoring on/off via chat
🔒 Private by Design
All processing happens on-device — no API keys, no third-party services
🔋 Battery Optimized
IMAP IDLE replaces polling; background service uses minimal resources
⚡ Always On
Foreground service with boot receiver — restarts automatically after reboot
📱 Requirements
Android 13+ (API 33)
Snapdragon 8 Gen 2 / Gen 3 recommended (arm64-v8a)
6–8 GB RAM recommended for 4B+ parameter models
Gmail account with App Password enabled
Google Voice account (optional — for GVoice SMS routing)
🚀 Getting Started
1. Download the APK
Grab the latest release from the Releases page and install it on your device. Enable "Install from unknown sources" if prompted.
2. Run the Setup Wizard
On first launch, Aigentik walks you through:
Your name and agent name
Your phone number (used to identify your commands)
Aigentik's number (Google Voice or direct SIM)
Gmail address and App Password for monitoring
Gmail App Password:
Go to myaccount.google.com → Security → 2-Step Verification → App Passwords.
Generate a password for "Mail". Enter the 16-character code — spaces are handled automatically.
3. Load an AI Model
Navigate to AI Model in the app and either:
Download a recommended model directly in-app, or
Browse to a .gguf file already on your device
Recommended starting model:
Qwen3-1.7B-Q4_K_M.gguf (~1.1 GB)
4. Start Chatting
Open the Chat screen. Aigentik is ready when you see AI Ready 🟢.
💬 Chat Commands
Aigentik understands natural language. Examples:
Copy code

text Mom I'll be home by 7
email John saying the meeting is confirmed
find Sarah
what's Mike's phone number?
check my emails
status
stop monitoring email
start google voice
never reply to spam caller
always reply to DJ
channels
📡 Channel Management
Command
Effect
stop sms / start sms
Pause/resume direct SMS monitoring
stop google voice / start google voice
Pause/resume GVoice email routing
stop email / start email
Pause/resume Gmail monitoring
stop all / start all
Pause/resume all channels at once
channels
Show current status of all channels
🏗️ Architecture
Copy code

┌─────────────────────────────────────────────┐
│              AigentikService               │
│         (orchestrates all engines)         │
└──────┬──────────┬──────────┬────────────────┘
       │          │          │
  ┌────▼───┐ ┌───▼────┐ ┌───▼──────────────┐
  │  SMS   │ │ Email  │ │   AI Engine       │
  │Adapter │ │Monitor │ │  (llama.cpp JNI)  │
  └────┬───┘ └───┬────┘ └───────────────────┘
       │          │
  ┌────▼──────────▼────────────────────────────┐
  │              MessageEngine                │
  │   (admin commands + public auto-reply)    │
  └──────────┬──────────────────┬──────────────┘
             │                  │
      ┌──────▼──────┐   ┌───────▼──────┐
      │  SmsRouter  │   │  EmailRouter  │
      │ (SmsManager)│   │(GVoice/SMTP)  │
      └─────────────┘   └──────────────┘
Key design decisions:
Dual IMAP stores — separate persistent connections for IDLE (read-only) and polling (read-write)
E.164 phone formatting — outbound SMS numbers formatted to +1XXXXXXXXXX
Channel-aware routing — replies go back through the channel they arrived on
Context reset between generations — prevents KV cache overflow
🔧 Building from Source
Prerequisites
Copy code

# On Termux (Android)
pkg install git

# Or on Linux/macOS
# Android Studio + NDK 27.2.12479018
Clone and Build
Copy code

git clone https://github.com/Ishabdullah/aigentik-android.git
cd aigentik-android
Push to main and GitHub Actions builds the APK automatically.
Native Layer
Requires:
NDK 27.2.12479018
CMake 3.22+
-march=armv8.4-a+dotprod+fp16+i8mm
🛡️ Privacy & Security
No cloud inference — runs fully on-device via llama.cpp
No telemetry
Gmail credentials currently stored locally (future: Android Keystore + OAuth2)
SMS interception without replacing default SMS app
🗺️ Roadmap
[x] On-device inference
[x] SMS auto-reply
[x] Google Voice routing
[x] Gmail IMAP IDLE monitoring
[x] Channel toggle commands
[ ] OAuth2 integration
[ ] Android Keystore credential storage
[ ] Multi-model hot-swap
[ ] Scheduled messages
[ ] Web dashboard companion
📄 License
Personal use only. All rights reserved © Ismail Abdullah 2026.
🙏 Acknowledgements
llama.cpp
Qwen3
Jakarta Mail
Built on a Samsung Galaxy S24 Ultra · Powered by llama.cpp · Made by Ish
