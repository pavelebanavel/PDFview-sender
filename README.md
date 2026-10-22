# DocsView Sender

Android phone app that sends a document to the PDFview watch app over a Samsung
Accessory Bluetooth connection. It is the **sender** half of
[v1ack/PDFview](https://github.com/v1ack/PDFview); the Tizen watch app lives in
that repository and is not included here.

## What it does

- Type or paste `.txt` / `.md` content, name the file, and send it to the watch.
- Or pick an existing document (PDF, TXT, Markdown) from storage. A picked
  document is sent as-is; the name and content fields are ignored.
- Shows connection state, transfer progress, and lets you cancel a transfer.

## Stack

- Kotlin + Jetpack Compose, Material 3 Expressive (`MaterialExpressiveTheme`,
  `ToggleButton`, `LoadingIndicator`).
- `minSdk 26`, `compileSdk 35`.
- UI localized in English and Russian.

## Build

The Samsung Accessory SDK jars are required and are not on Maven. Put them in
`app/libs/`:

```
app/libs/accessory-v2.6.4.jar
app/libs/sdk-v1.0.0.jar
```

Then:

```sh
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

A phone with Samsung Accessory support (or a device with the companion watch
app installed and connected) is needed for an actual transfer; without it the
app reports that the watch is not connected.
