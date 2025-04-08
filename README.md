    # Karoo Route Graph Extension

[![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/timklge/karoo-routegraph/android.yml)](https://github.com/timklge/karoo-routegraph/actions/workflows/android.yml)
[![GitHub Downloads (specific asset, all releases)](https://img.shields.io/github/downloads/timklge/karoo-routegraph/app-release.apk)](https://github.com/timklge/karoo-routegraph/releases)
[![GitHub License](https://img.shields.io/github/license/timklge/karoo-routegraph)](https://github.com/timklge/karoo-routegraph/blob/master/LICENSE)

This extension for Karoo devices adds a graphical data field showing a graph of the current route,
including POIs / checkpoints / refueling stops.

Compatible with Karoo 2 and Karoo 3 devices.

![Settings](preview0.png)
![Field setup](preview1.png)
![Data page](preview2.png)

## Installation

If you are using a Karoo 3, you can use [Hammerhead's sideloading procedure](https://support.hammerhead.io/hc/en-us/articles/31576497036827-Companion-App-Sideloading) to install the app:

1. Open the [releases page](https://github.com/timklge/karoo-routegraph/releases) on your phone's browser, long-press the `app-release.apk` link and share it with the Hammerhead Companion app.
2. Your karoo should show an info screen about the app now. Press "Install".
3. Open the app from the main menu and acknowledge the API usage note.
4. Set up your data fields as desired.

If you are using a Karoo 2, you can use manual sideloading:

1. Download the apk from the [releases page](https://github.com/timklge/karoo-routegraph/releases) (or build it from source)
2. Set up your Karoo for sideloading. DC Rainmaker has a great [step-by-step guide](https://www.dcrainmaker.com/2021/02/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html).
3. Install the app by running `adb install app-release.apk`.
4. Open the app from the main menu and acknowledge the API usage note.
5. Set up your data fields as desired.

## Usage

After installing this app on your Karoo and opening it once from the main menu, you can add the following new data fields to your data pages:

TODO

## Credits

- Uses valhalla1.openstreetmap.de for elevation data
  - Daten © <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>-Mitwirkende (<a href="https://opendatacommons.org/licenses/odbl/index.html">ODbL</a>), <a href="https://creativecommons.org/licenses/by-sa/2.0/">CC-BY-SA</a>, <a href="https://openstreetmap.org/fixthemap">mitmachen/Fehler melden</a>
- Icons are from [boxicons.com](https://boxicons.com) ([MIT-licensed](icon_credits.txt))
- Uses [karoo-ext](https://github.com/hammerheadnav/karoo-ext) (Apache2-licensed)
