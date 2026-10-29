# TheUnderScannerApp

An Android app to remotely scan, download, and visualize LiDAR point cloud data.

## Current Version

`v0.1.0` — Early development stage (features subject to change)

## Features

- Connect to a remote server hosted on an SBC which is plugged to the LiDAR (cf [TheUnderScannerServer Repo](https://github.com/UnderScanner/TheUnderScannerServer))
- Look into local and remote scans folder
- Download `.pcd` (point cloud) scan files with progress tracking
- Homemade 3D viewer: View `.pcd` file with OpenGL (chunked VBOs, intensity / height / tag coloring, LiDAR trajectory)
- Start / stop a scan and watch the map build live (WebSocket preview, accumulated on the phone)
- Per-scan health charts: Jetson temperature / load / power / SLAM rates, plus live-view rendering and WiFi KPIs
- Clean UI

## Project Structure

- `app/src/main` — Main Android application
- Assets and scans are managed dynamically
- Network layer uses Kotlin Coroutines and OkHttp

## Installation

1. Clone this repository
2. Open with Android Studio
3. Sync Gradle and run on device or emulator

## Disclaimer

This is a work-in-progress project.

See `CLAUDE.md` for the architecture, the Jetson API contract and the known TODOs.

---

Made with ❤️ by Théotime Dmitrašinović
