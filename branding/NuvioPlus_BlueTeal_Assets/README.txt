NUVIO+ — BLUE / TEAL LIVE PLAY ASSET PACK

START HERE
Brand/Logo: transparent and navy-backed full logos for app screens, headers and splash screens.
Brand/Symbol: transparent symbol-only PNGs. App icons use the symbol without tiny text.
iPhone_iPad: 1024px opaque source plus a traditional AppIcon.appiconset covering iPhone and iPad sizes.
Mac: NuvioPlus.icns and a traditional Xcode AppIcon.appiconset, including 1024px artwork.
Windows: multi-resolution NuvioPlus.ico (16–256px), individual PNGs, and tile artwork.
AppleTV: foreground/background PNG layers at 400x240, 800x480 and 1280x768; flattened previews; top-shelf images.
Apple_IconComposer: aligned 1024px foreground and background PNGs plus monochrome foreground for current Apple tooling.

DEVELOPER SETUP
iOS/iPadOS: import the appiconset into Assets.xcassets or use AppIcon_1024.png in the single-size AppIcon slot. Files are opaque and have no pre-cut rounded corners; the OS provides the mask.
Mac: use the macOS appiconset in Xcode or the ICNS in desktop frameworks. For current layered icon workflows, use the separate Icon Composer input layers.
Windows: set NuvioPlus.ico as the executable/application icon. PNG and tile files support common desktop frameworks; apply the naming/scaling conventions of your specific package manifest.
Apple TV: create App Icon and App Store icon image stacks in Xcode, then import matching background and foreground layers into their size slots. Do not use flattened previews as layered assets. Import top-shelf images into the corresponding slots if your app uses static top-shelf imagery.
Icon Composer: import the aligned foreground and background, set appearance/material properties and export an .icon document on a Mac. This pack provides PNG inputs, not a precompiled .icon or .lsr file.

COLOUR
Navy background: #030C24. Blue/cyan/teal gradients are preserved from the selected artwork. Wordmark is white. Use the supplied artwork rather than resetting its text in an approximate font.

SOURCE AND QUALITY
Artwork was extracted from the approved blue–teal concept, preserving its shape and colours. Native symbol is approximately 320x350px, and the wordmark approximately 440x110px. Larger PNGs are high-quality raster resamples, not newly drawn vector masters. No SVG or editable font master is included. This is an implementation asset pack; final appearance should be checked on physical devices and in Xcode, Icon Composer, and your Windows packaging workflow. No compiled application or store submission validation was performed.

OFFICIAL REFERENCES
https://developer.apple.com/documentation/xcode/configuring-your-app-icon
https://developer.apple.com/documentation/Xcode/creating-your-app-icon-using-icon-composer
https://developer.apple.com/design/human-interface-guidelines/app-icons/
