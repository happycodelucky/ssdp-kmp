---
title: Stop forcing compileSdk 37 on Android consumers
change: patch
description: The Android AAR now declares minCompileSdk 34 instead of inheriting the library's own compileSdk 37.
---

AGP writes a library AAR's `minCompileSdk` from the compileSdk the library was
built with, and every consuming app's `check<Variant>AarMetadata` enforces it.
Through v0.7.0 that was 37 — raised only for our sample app — so any app on
compileSdk 36 or lower failed its release build with an AAR-metadata error.

The AAR now declares `minCompileSdk = 34` (Android 14) explicitly. Apps on
compileSdk 34 or newer can consume ssdp again; nothing else changes, and there
is no API change. Transitive dependencies (e.g. `androidx.startup`) still carry
their own floors.
