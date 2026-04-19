# Anki UCM Prototype (Universal Contextual Miner)

A protype Android app made to show off the AnkiDroid API's capabilities. UCM lets users extract text and image clips from any screen and turn them into Anki flashcards right away, without having to switch contexts. This makes it possible to learn languages and other things as you go.

This project is being worked on as part of a proposal for **Google Summer of Code (GSoC) 2026**.

## Features
- **Context-Aware Floating UI:** A Floating Action Button (FAB) menu that you can move around and that opens up to show Text or Image + Text mining modes.
- **Universal Text Extraction:** Uses `MediaProjection` and on-device to get around the limits of the standard Android View Hierarchy. **OCR from Google ML Kit**- **Interactive Snipping Tool:** Lets users draw their own boundaries on their screen to choose what visual context to capture.
- **Performance Benchmarking:** Logs and tracks how long it takes for ContentResolver to run so that it can find architectural bottlenecks in the API when it is under heavy load.

## 🛠️ Tech Stack & Permissions

- **Language:** Kotlin
- **Architecture:** MediaProjection + Foreground Accessibility Service- **Used APIs:** Google ML Kit (Vision/Text)
- **Required Permissions:**
  - `AccessibilityService` (For keeping the overlay alive and handling tree fallbacks)
  - `MediaProjection` (To grab pixels on the screen)

## 📥 Getting Started

1. Clone the repo.
2. Build and install the app on an Android device with API 33+.
3. Open Android Settings -> Accessibility and turn on **Anki UCM Prototype**.
4. Tap the floating bubble and when the permission to capture the screen pops up, grant the permission.

## Tip
- Turn on accessibility shortcut to toggle service with ease