---
title: Expose kotlinx-coroutines and androidx.startup as api dependencies
change: patch
description: The published metadata now puts kotlinx-coroutines-core (and, on Android, androidx.startup) on consumers' compile classpath, matching the types the public API exposes.
---

SsdpClient.devices and changes are a StateFlow and a SharedFlow, and the Android SsdpInitializer implements androidx.startup's Initializer, but both libraries were published as runtime-only (implementation) dependencies. A consumer that collected the flows without declaring kotlinx-coroutines itself could fail to compile. They're now api dependencies of ssdp (and coroutines of ssdp-testing), so no consumer change is needed; builds that already declare coroutines are unaffected.
