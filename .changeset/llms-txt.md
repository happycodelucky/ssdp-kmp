---
title: Ship llms.txt and llms-full.txt inside every published artifact
change: minor
description: Every published jar and the Android AAR now carry llms.txt and llms-full.txt, the module's public API with its KDoc, for AI coding tools.
---

Each module generates the pair from its Dokka Markdown output at publish time
and packs it into every jar (jvm, metadata, sources, javadoc) and the AAR under
`META-INF/com.happycodelucky.ssdp/<artifactId>/`:

- `llms.txt` indexes the artifact: what it is, its coordinates, and links to
  `llms-full.txt`, the README (installation and per-platform setup) and the
  changelog.
- `llms-full.txt` is the module's full public API with its documentation,
  stamped with the version it ships in.

An AI tool that has the dependency (in a Gradle cache, a sources jar or an AAR)
can read the library's API without network access. The files are namespaced by
coordinates, so they can't collide with another library's `llms.txt` on your
classpath. In the AAR they sit at the archive root, so they never end up in your
APK. There is no API change and nothing to do.

`minor` rather than `patch`: this adds new artifact contents rather than fixing
anything.
