# Third-party dependency provenance

No model weights or trained detector are used. Downloaded binary dependencies and videos
are intentionally excluded from Git.

| Component | Version / identity | Source / use |
|---|---|---|
| Temurin JDK | 21.0.12.1+1, Windows x64 | [Official release](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.12.1%2B1); private application runtime |
| Apache Maven | 3.9.16 | [Apache distribution](https://archive.apache.org/dist/maven/maven-3/3.9.16/); build only |
| ImageJ 1 | 1.54p | Maven Central `net.imagej:ij`; processors and TIFF interoperability |
| AnimalTracker | official JAR, SHA256 below | [Official site](https://animaltracker.elte.hu/); connected components through its Java API |
| JavaCV / JavaCPP | 1.5.14 | [Bytedeco](https://github.com/bytedeco/javacv); native bindings |
| FFmpeg preset | 8.1.2-1.5.14 | Maven Central, Windows x64 only; decoding |
| OpenCV preset | 4.14.0-1.5.14 | Maven Central, Windows x64 only; CPU optical flow |
| OpenBLAS preset | 0.3.34-1.5.14 | Maven Central; OpenCV native dependency |
| Gson | 2.13.2 | Maven Central; JSON projects |
| JUnit Jupiter | 5.11.4 | Maven Central; tests only |
| Fiji | Windows x64 archive served 2026-10-07 | [Official download](https://downloads.imagej.net/fiji/latest/fiji-latest-win64-jdk.zip); optional external scientific review |

AnimalTracker SHA256:
`286e56cc1d582c881ef6364c1a571195c400b176666fa24c430a6ef28a93e64f`.

Fiji archive SHA256:
`8e33a11c7e79eb02c055bcee40de806d16e59f0b42e58c4689bd23eb47d990bb`.

The complete pinned setup hashes are in `setup.ps1`. Fiji's upstream `latest` URL and
AnimalTracker's unversioned JAR URL are mutable; setup fails closed if their content changes.

AnimalTracker's [source repository](https://github.com/gulyasmarton/AnimalTracker)
contains an MIT license. Retain upstream notices; the download is not relicensed by this project.
The adapter does not use upstream `BaseBlob.getPoints()`: inspection found that method
adds the bounding rectangle's x coordinate to the y coordinate. Iterating mask + bounds avoids it.

Fiji is a collection of separately licensed components. Its installed `licenses/` directory,
the JDK `legal/` directory, and Maven/JAR notices remain intact. Before distributing a bundled
installer, inventory the exact native FFmpeg build and all redistribution obligations.
This source-only prototype does not declare the complete third-party bundle to have one license.
