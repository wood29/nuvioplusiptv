# AvonioTV — brand and developer guide

## Identity
Product name: **AvonioTV**. Logo lettering: **avonioTV** (lowercase avonio, uppercase TV). The three flowing bands combine a play shape with broadcast waves. Use the supplied wordmark; no editable font master was supplied with the approved generated artwork.

## Choose the correct layout
| Use | Preferred artwork |
|---|---|
| TV dashboard header, desktop toolbar, website header | Horizontal colour logo on navy |
| Welcome, launch, splash or presentation screen | Stacked logo |
| Phone/tablet home screen, OS launcher, small avatar | Symbol-only icon |
| Android TV launcher banner | Full horizontal logo with wordmark |
| Apple TV launcher | Symbol layered icon; horizontal and stacked alternatives also supplied |
| Light backgrounds | Navy monochrome logo |
| Dark photography | White logo with a sufficiently dark backing area |

## Colour palette
| Token | HEX | RGB | Use |
|---|---|---|---|
| Midnight navy | #030C24 | 3, 12, 36 | Main background |
| Electric blue | #0066FF | 0, 102, 255 | Gradient beginning |
| Cyan | #00C8ED | 0, 200, 237 | Gradient midpoint |
| Teal | #00E5BF | 0, 229, 191 | Gradient end and TV accent |
| White | #FFFFFF | 255, 255, 255 | Wordmark and primary text |
| Light surface | #F4F7FB | 244, 247, 251 | Optional light UI background |
| Muted text | #A7B4CC | 167, 180, 204 | Secondary text on navy |

These are standardized implementation colours chosen to match the appearance of the approved logos. The original gradient contains intermediate shades and mild image texture. Use supplied PNGs for the exact approved appearance. CSS gradient: linear-gradient(45deg, #0066FF 0%, #00C8ED 52%, #00E5BF 100%). All exported PNGs include an sRGB profile. CMYK values are intentionally omitted because print conversion depends on the printer profile.

## Typography
The wordmark is supplied as artwork, not a named commercial typeface. Do not retype or reconstruct it with an approximate font. For app UI use native system fonts: Apple system font on Apple platforms; Roboto/system sans-serif on Android; Segoe UI/system sans-serif on Windows. Suggested weights: 400 body, 600 labels, 700 screen headings. Fonts are not bundled.

## Spacing and minimum sizes
Keep at least one lowercase letter-height of clear space around a full logo where the layout permits; never place controls against the symbol. Preserve the PNG's aspect ratio. Recommended minimum on-screen sizes: horizontal logo 180 logical pixels wide; stacked logo 160 logical pixels wide; symbol 24 logical pixels high. These are starting recommendations, not platform requirements. Use symbol-only artwork below these full-logo limits. Check readability on a real TV from normal viewing distance. Avoid stretching, rotation, extra outlines, glow or drop shadows. Never put a dark monochrome logo on navy. No plus sign is part of this identity.

## Asset folders
01_Approved_Originals: the two approved images unchanged, for reference.
02_Logos: stacked/horizontal/symbol/wordmark, transparent colour, white, navy, and colour on navy, with native and multiple width exports.
03_Universal_Icons: symbol-only icons, opaque and transparent, 16–2048px.
04_Apple: iPhone/iPad and Mac Xcode icon sets, ICNS, Icon Composer input layers and Apple TV assets.
05_Android_TV: density-based launcher icons, TV banners, adaptive layers/XML, Play Store artwork and manifest example.
06_Windows: multi-resolution ICO, PNGs and tile artwork.
07_TV_Dashboard: transparent header logos and full-screen launch backgrounds from 640x360 through 3840x2160.
08_Brand_Guide: this guide, an offline visual guide, CSS and JSON colour tokens.

## iPhone and iPad
Use AppIcon_1024.png for a single-size icon slot, or import the supplied traditional AppIcon.appiconset into Assets.xcassets. Every iOS app icon is RGB, opaque and square with no baked rounded corners. Supplied sizes cover iPhone, iPad and App Store slots. The OS provides its mask. The transparent universal icons are for in-app UI, not iOS App Store icon slots.

## Mac
Use 04_Apple/Mac/AvonioTV.icns in desktop frameworks or the supplied macOS AppIcon.appiconset in Xcode. The legacy macOS icon includes a rounded navy tile with transparent outer margins. For current layered rendering, import the separate 1024px foreground and background from Icon_Composer_Inputs into Icon Composer, choose materials/appearance settings there, and export the .icon file on a Mac. No compiled .icon file is included. The monochrome foreground is an input for tinted/clear variants, not a finished appearance set.

## Android TV and Google TV
Merge res into your Android app resources and apply the manifest attributes shown in AndroidManifest_example.xml. Review resource names before replacing project files. Launcher icons: 80, 120, 160, 240 and 320px. Text-bearing banners: 160x90, 240x135, 320x180, 480x270 and 640x360px. These are flat banner resources. Adaptive launcher icon XML is included for API 26+, plus an optional monochrome layer for API 33+ on supported Android form factors. Android TV itself does not currently use themed icons. Foreground images are centered on 108dp canvases with conservative padding to preserve the mark under circular masks. A legacy banner is supplied; a separately compiled adaptive TV banner is not included. Play Store icon is 512px and the feature graphic is 1024x500px.

## Apple TV
For each of symbol, horizontal and stacked layouts, PNG foreground/background pairs are included at 400x240, 800x480 and 1280x768. Use the matching pair in Xcode's small icon and App Store image stacks. A flattened preview sits beside every pair for reference; it is not a substitute for image-stack layers. Start with the symbol layout for best home-screen recognition. Top-shelf artwork is included at 1920x720, 3840x1440, 2320x720 and 4640x1440. Use sizes appropriate to the project's target. Check focus/parallax cropping on a device. No compiled .lsr, Xcode project or App Store submission is included.

## Windows PC
Set AvonioTV.ico as the executable/application icon in your framework. It contains 16, 20, 24, 32, 40, 48, 64, 128 and 256px images. The PNG and tile images are ready inputs; MSIX/UWP manifests may need additional filenames, scale/targetsize qualifiers and framework-specific configuration. No compiled executable or installer is included.

## TV dashboard
Use a transparent horizontal logo in the top navigation. Pick the next larger export for high-density displays and render at the intended logical size. Full-screen 16:9 stacked/horizontal launch images are included for HD, Full HD, QHD and 4K screens. Keep the logo in the central safe area; place controls and channel content outside its clear space. These are branding backgrounds, not screenshots of an implemented dashboard.

## Source resolution and validation
The approved source images are raster: stacked 1254x1254 and horizontal 1774x887. Native transparent crops and exact source dimensions are listed in manifest.json. Large exports are high-quality Lanczos resamples; they are not vector redraws or additional native detail. No SVG/font master is included. This preserves the selected design without a generated reinterpretation. PNG dimensions, image decoding, alpha rules, icon-container sizes and ZIP integrity were checked. No Android/Xcode/Windows app build, store upload, physical-device or parallax runtime validation was performed.

## Official implementation references
- https://developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines
- https://developer.android.com/develop/ui/compose/system/icon_design_adaptive
- https://developer.apple.com/documentation/xcode/configuring-your-app-icon
- https://developer.apple.com/documentation/Xcode/creating-your-app-icon-using-icon-composer
- https://developer.apple.com/design/human-interface-guidelines/app-icons/
