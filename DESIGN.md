# Gallery design

## Structure

The library opens directly after unlock. The header names the current view, shows the current source, and provides access to settings. The search field and filters sit together above the library. Images and videos occupy the grid, with secondary actions in the library menu.

Library, Saved, Downloads, and Sources appear in the same order on the bottom bar. At widths of at least 600 dp and heights of at least 340 dp, the bar becomes a navigation rail. The media viewer remains a focused full-screen surface with an explicit return action. Returning from it restores the library viewport.

Saved lists the current source's collections. Sources shows the active source and its connection state, with a direct configuration action. Other sources can be selected there or from the library header. Downloads shows the current library's queue, errors, and offline copies. When a source has no offline feature, the page provides a route to select another library.

## Color and type

The app uses Android's sans-serif type and the dark roles in `res/values/colors.xml`. The gallery surface is #141715, raised surfaces use #1D221E, and the muted green accent is #BDC9A4. Main text is #E8ECE5. Selected destinations use an accent icon and a #303A29 background, with a spoken selected state.

The shared toolbar uses a 24 sp title. Directory rows use 16 sp for the action and 13 sp for secondary state. Icons have 48 dp touch targets. The navigation labels allow two lines and grow with font size.

## Interaction

The source selector is a bottom sheet. Source-specific features appear in their context, such as chat folders in the channel browser and saved tag searches in web libraries. Settings use the same rows as the Sources and Saved destinations. Empty states offer a relevant next action, such as importing a file, reviewing filters, or configuring a source.

Media and queue operations keep their existing asynchronous paths. The shell preserves the active library while opening a secondary tab. Viewer gestures, search filters, and connection actions have visible or accessible alternatives. Privacy is conveyed through clear controls and protected windows.
