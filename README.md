# MeshCore Teletext for Android

This app reads Markdown pages from a MeshCore Teletext server using a BLE companion. This directory is a standalone Android project and can be moved into its own repository. The companion must share compatible radio settings with the server companion and be able to exchange direct messages with it.

## Requirements and build

- Android Studio with Android SDK 36 and JDK 17
- An Android device running Android 8.0 (API 26) or newer
- A MeshCore BLE companion

Open this directory in Android Studio, or build and install a debug APK from here:

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The app uses [MeshCoreKmp](https://github.com/Wavesonics/MeshCoreKmp) for BLE scanning, companion setup, contacts, and direct messages.

## Connect and read pages

On launch, grant Bluetooth and location permissions in the companion screen, then select a MeshCore companion card. Discovery uses the MeshCore BLE service UUID, so devices that do not advertise that service are hidden. The app shows connection progress before loading the companion's contacts.

The server screen lists contacts whose names end in `-txt`, hiding the suffix in the cards and selected-server label. Cards appear as contacts arrive, so you can select a server before the full list finishes loading. Reopening the screen uses the cached list. If a server is missing, add or import its full public key as a contact with a MeshCore client, then tap **Refresh**. On connection, the app sends a flood advertisement so the server companion can learn the Android companion's key. A fresh server advertisement is unnecessary if the Android companion already has that contact.

Select a server to open page 100. The app remembers the selection and loads page 100 again after reconnecting. Choose an indexed page from the number field's dropdown, or enter any number from 100 to 999 and tap **Go**. Numbered page links and bottom-strip suggestions also open pages. The top-right button refreshes the current page while idle or cancels an active transfer. A failed transfer offers **Retry**. Android's system Back restores the previously opened page from local cache, cancelling an active request first; when no earlier page remains, it opens server selection. The toolbar Back button opens server selection directly, whose Back button opens companion selection.

Completed pages are kept in temporary app storage and displayed in 32 KiB sections. Use **Previous section** and **Next section** in the bottom strip for long pages. The strip suggests up to five pages: **Index** first when it is not already open, then linked index pages, prioritizing the 200, 300, 400, and other hundred-numbered pages. The current page is omitted. The renderer handles headings, lists, emphasis, code blocks, inline code, and numbered page links; other Markdown syntax is shown as text.

## Protocol and compatibility

See [PROTOCOL.md](PROTOCOL.md) for the T1 frame format, chunking, compression, repair, and checksum rules. The app's copy of `protocol.tsv` lives in `app/src/test/resources/`, so unit tests work when this directory stands alone. Keep that fixture and protocol documentation synchronized with the server repository. Update both projects together when changing compressed frames.

## Tests

```sh
./gradlew testDebugUnitTest assembleDebug
```

The tests cover shared protocol examples, page reassembly, index suggestions, and page history. A full USB-to-radio-to-BLE test requires the server, two compatible companions, and an Android device.
