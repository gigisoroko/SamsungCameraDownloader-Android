# Samsung Camera Downloader for Android

Android app for downloading photos and videos from legacy Samsung Wi-Fi cameras.

The app provides a simple Spanish interface for discovering compatible Samsung
cameras over Wi-Fi, browsing their media files, selecting files, and downloading
them directly to the Android gallery.

## Features

- Samsung Wi-Fi camera discovery
- UPnP/DLNA camera communication
- Browse photos and videos stored on the camera
- Select individual files
- Select the last 5 files
- Select the last 10 files
- Select all files
- Download selected files
- Files are saved to the Android gallery under `Samsung Camera`
- Spanish interface

## Tested Camera

### Samsung WB35F

Tested successfully with:

- Samsung WB35F
- Android 13
- Motorola Moto G52

## How to use

### 1. Enable Wi-Fi sharing on the camera

On the camera, open the Wi-Fi sharing function and select:

**Share via Smartphone / ShareLink**

The exact wording may vary depending on the camera firmware.

The camera will create its own Wi-Fi network.

### 2. Connect the Android phone

Connect the Android phone to the Wi-Fi network created by the camera.

The phone does not need internet access while communicating with the camera.

### 3. Open the app

Open Samsung Camera Downloader.

Tap:

**Buscar cámara**

Wait for the camera to be discovered.

### 4. Select files

The application displays the files available on the camera.

You can:

- select individual files;
- tap **Últimas 5**;
- tap **Últimas 10**;
- tap **Todas**;
- tap **Ninguna** to clear the selection.

### 5. Download

Tap:

**Descargar seleccionadas**

The selected photos and videos will be downloaded to:

**Gallery → Samsung Camera**

## Compatibility

The application is intended for legacy Samsung cameras that expose their
media library through the Samsung Wi-Fi / UPnP/DLNA functionality.

Compatibility may vary between camera models.

This project has currently been tested with the Samsung WB35F.

## How it works

The application communicates directly with the camera over the local Wi-Fi
network.

The communication is based on UPnP/DLNA:

1. The application searches for the camera using SSDP.
2. It retrieves the camera's UPnP device description.
3. It locates the ContentDirectory service.
4. It uses the UPnP `Browse` action to retrieve the camera's media library.
5. Media URLs are obtained from the DIDL-Lite responses.
6. The selected files are downloaded over HTTP.

No Samsung cloud service is required.

## Credits

This project was inspired by the reverse-engineering and protocol research
published by Istvan Safar in:

https://github.com/IstvanSafar/SamsungCameraDownloader

His work documented how legacy Samsung Wi-Fi cameras expose their files
through UPnP/DLNA and provided an important reference for understanding
the communication protocol.

Thank you to Istvan Safar for documenting this protocol and making the
research available to the community.

This repository is an independent Android implementation and is not the
original Samsung application.

## License

This project is released under the MIT License.

See `LICENSE` for details.

## Development Environment

This project was developed and built entirely on an Android phone using AndroidIDE.

No desktop computer was required to develop or build the application.

### Development device

- Device: Motorola Moto G52
- Android: Android 13
- Architecture: ARM64
- IDE: AndroidIDE

### Build environment

- Gradle: 7.5
- Android Gradle Plugin: 7.4.2
- JDK: OpenJDK 11.0.28 (Temurin)
- compileSdkVersion: 35
- targetSdkVersion: 35
- minSdkVersion: 26
- Java source compatibility: Java 8
- Java target compatibility: Java 8

### Build the APK

From the project root, run:

```bash
./gradlew clean
./gradlew assembleDebug
```

The generated APK is located at:

```text
app/build/outputs/apk/debug/app-debug.apk
```
