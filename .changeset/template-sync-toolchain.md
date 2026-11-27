---
title: Update dependencies and toolchain
change: patch
description: Kotlin 2.4.21, Ktor 3.6.2, kotlinx.serialization 1.13.0, reachable 0.15.0 and the rest of the dependency set move to their latest stable releases.
---

Built with Kotlin 2.4.21 and SKIE 0.10.16, and depends on Ktor 3.6.2, kotlinx.serialization 1.13.0,
kotlinx-io 0.9.3, xmlutil 1.0.2.1, Kermit 2.2.0 and reachable 0.15.0. No public API changed
(the jvm and klib ABI dumps are identical). The AAR's declared `minCompileSdk` is still 34.
