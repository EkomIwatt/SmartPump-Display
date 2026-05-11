# SmartPump Display — Android App Scaffolding

## Project context

I'm building an Android app for "SmartPump," a system that turns Nigerian fuel dispensers into smart payment terminals. The app runs in kiosk mode on a tablet bolted to the dispenser. It reads fuel-flow pulses from a microcontroller (Arduino/STM32) over USB serial, displays litres dispensed in real time, accepts payments (NFC, QR, USSD), and controls a relay that locks/unlocks the pump.

This is a safety-critical, anti-fraud system deployed in environments with frequent power cuts and unreliable internet. Every state transition must survive a power loss. Every transaction must be recoverable.

I'm the Android engineer. My firmware partner is building the Arduino adapter separately — I will mock it for now.

## Tech stack (locked, do not change)

- Kotlin
- Jetpack Compose (Material 3)
- Min SDK 26, target SDK 34
- Kotlin DSL for Gradle (build.gradle.kts)
- Hilt for dependency injection
- Room for local persistence
- Kotlin Coroutines + Flow for async
- WorkManager for background sync
- usb-serial-for-android for USB OTG serial
- ZXing (com.journeyapps:zxing-android-embedded) for QR generation
- Sentry for crash reporting (set up the dependency, leave DSN as a TODO)
- Sealed classes for state machines (no third-party FSM libraries)

## Architecture

Clean architecture with three layers:
- `data/` — Room DAOs, USB serial readers, payment processor implementations
- `domain/` — Use cases, sealed state classes, domain models, repository interfaces
- `ui/` — Compose screens, ViewModels, theme

Plus utility packages:
- `hardware/` — PulseSource interface + Mock and UsbSerial implementations
- `payment/` — PaymentProcessor interface + Mock implementation
- `di/` — Hilt modules

## Module / package structure to generate