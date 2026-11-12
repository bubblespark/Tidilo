# Developing app themes

Use this guide to add a selectable preset, tune an existing preset, or connect a component to the theme. See [UI design](ui_design.md#83-resolved-theme-and-shared-surface-contract) for the visual contract and component coverage.

## Choose the editing point

All Kotlin paths in this guide are relative to `app/src/main/java/me/bubblespark/tidilo/`.

| Change | Editing point |
| --- | --- |
| Selectable theme name and persisted identity | `config/AppTheme.kt` and the default and Japanese string resources |
| Values specific to one preset | The preset and `forTheme` in `ui/theme/AppThemeStyle.kt` |
| Shared shape defaults | `ui/theme/ThemeShapeScale.kt` |
| Material palette selection or provider behavior | `ui/theme/Theme.kt` |
| Role defaults, state precedence, and decoration derivation | `ui/theme/ThemeSurfaces.kt` |
| Drawing order, clipping, and native surface integration | `ui/components/TidiloSurface.kt` |
| Shared typography | `ui/theme/Type.kt` |

The saved selection flows from `AppConfig` to `TidiloTheme`. The provider selects a Material color scheme, applies the preset's shape scale, and provides resolved surface styles. Consumers use `MaterialTheme` for native attributes or request a `SurfaceRole` with the appropriate variant and semantic state. Keep the navigation and editor subtree at the same composition identity during theme changes so its state survives.

## Add a selectable preset

1. Add an entry to `AppTheme` with a unique, stable lowercase `code` and a `labelRes`. Add the label to `app/src/main/res/values/strings.xml` and `app/src/main/res/values-ja/strings.xml`. Codes are persisted under `app_theme` and parsed case-insensitively. Preserve existing codes across label changes. Missing or unknown codes fall back to Default.
2. Define a preset in `AppThemeStyle.kt`. Start from the preset with the closest behavior, set its `theme` to the new enum entry, and specify the differences. Register it in the exhaustive `AppThemeStyle.forTheme` mapping.
3. Open Settings > Theme and check the label, selected state, and restoration after reopening the app. The chooser enumerates `AppTheme.entries`. Update explicit enum, mapping, and string expectations in tests where applicable.
4. Verify the new preset in light and dark mode. Check wallpaper-derived colors if the preset inherits Dynamic Color. Exercise both untouched presets to catch changes to shared defaults.

## Tune an existing preset

Start with an affected component and identify its role, variant, state, and rendering path. Section 8.4 of [UI design](ui_design.md#84-component-coverage-and-supported-parameters) maps the component families to their roles. Search for the role at the call site and trace which fields it consumes before changing a preset.

| Preset setting | What it controls |
| --- | --- |
| `lightMaterialColors`, `darkMaterialColors` | Full Material color scheme for the corresponding mode. A null value inherits the provider's Dynamic Color or built-in fallback. Set matching foreground and container pairs when supplying a palette. |
| `surfacePalette` | Light and dark canvas colors and shared container defaults for roles that read the palette. The canvas and Material background are separate values. |
| `shapeScale` | Named corner sizes used by Material shape slots and consumers that use the scale. A role or component with an explicit shape can use a different radius. |
| `colorRole` | Source of the ambient lighting color, including the contextual accent or a Material color role. |
| `cardStyle` | Treatment, depth, shape, gradient fill, and accent rim policy for the shared bookmark and front deck cards. Back deck layers have their own role. |
| `chipStyle` | Display badge treatment, depth, shape, gradient fill, category or unified color policy, category and subcategory icon tint, and light and dark neutral and content colors. Editor and filter chips have separate native role variants. |
| `floatingBarStyle` | Bar treatment, depth and gradient fill, active tab treatment, and the light and dark active tab color pair. The animated indicator has a separate resolution path. |
| `iconButtonStyle` | Plate style for primary, Like, and toolbar actions, plus decorated plate depth and gradient fill. Semantic icon tints can come from the component. |
| `headerAlpha`, `floatingBarAlpha`, `cardAlpha` | Background opacity defaults for the corresponding role families. Foreground content remains sharp and opaque unless its own state styling changes it. |
| `headerBlurRadius`, `floatingBarBlurRadius`, `cardBlurRadius` | Existing self-blur settings for the consumers that apply them. The affected layer blurs its own drawing. Treat backdrop blur as a separate rendering feature. |
| `surfaceOverrides` | Targeted overrides keyed by `SurfaceRole`. Use these for a single role rather than changing a family default. |

`SurfaceStyleOverride` accepts container and content colors, shape, treatment, elevation, border width, background opacity, gradient and bevel colors, ambient and spot shadow colors, and accent rim and wash. A null field inherits the resolved value. The map is keyed by role rather than mode, variant, or state. Use the light and dark palette fields for mode-specific values. A new mode-specific role setting requires explicit resolver support.

For example, to change the grid and list bookmark card corners and elevation in a preset, add this entry to its existing override map:

```kotlin
SurfaceRole.BOOKMARK_CARD to SurfaceStyleOverride(
    shape = RoundedCornerShape(24.dp),
    elevation = 2.dp,
)
```

This entry applies to both bookmark variants. Collection, domain, and RSS decks use separate deck roles. A change intended only for the compact grid variant belongs in variant resolution, with a check for the list variant.

Modern supplies both fixed Material schemes in `ModernColors.kt`. Its canvas and shared containers reuse those scheme tokens. Keep header colors tied to the Material background role so the existing scroll fade blends into the canvas.

Use `modernRaisedTextStyle` at eligible text call sites. Pass the existing text style and the control state so Default and disabled controls keep their existing styling. Shared page headers require explicit opt-in. Settings titles and controls use the ordinary text style.

## Understand precedence and renderer limits

- Begin with the Material palette and role and variant defaults. The resolver applies preset overrides and then enforces semantic rules where applicable. Check error and caution states, selected filters, and matched and warning badges. A neutral override can be superseded by a state-specific color pair. Animated navigation has its own resolution path and reapplies drawing overrides.
- Check the consumer as well as the resolver. Shared Surface consumers can use decorated fills, bevels, and shadows. Dialogs and sheets use resolved native attributes such as container and default shape. A shape specified by the caller takes precedence at that call site. Native controls and diagnostic content can inherit Material colors and shapes without using a shared Surface. Header fading and AI bubble decoration have specialized drawing paths.
- Keep treatment independent of elevation. A decorated surface with zero elevation still has its fill and edge. Use finite, nonnegative elevation and border widths, and opacity between zero and one. Preserve the shared ordering that draws shadows outside the content and ripple clip.
- Layout, spacing, component-specific typography, assets, asymmetric image masks, and specialized lighting geometry belong to the owning component or shared layout. The current preset model exposes no general typography, spacing, or motion scale. A new parameter needs a resolver rule and a consumer that uses it.
- Prefer an existing role and variant for a new consumer. Pass the semantic state and contextual accent to the resolver or the appropriate shared component. Use the resolved foreground with its container and preserve native input and accessibility semantics. Add a role when the design needs independent behavior, and record its consumers and supported attributes in the UI design coverage table.

## Add a blur-enabled overlay

Use `TidiloAlertDialog`, `TidiloModalBottomSheet`, or `TidiloDropdownMenu` from `ui/components` instead of their Material counterparts in screens. They retain native window, layout, dismissal, focus, scrolling, and gesture behavior. `LibraryNavigationRail` uses the same renderer on its native modal surface. Ordinary cards, headers, toolbars, screen backgrounds, OS dialogs, and keyboards are not blur targets.

```kotlin
TidiloAlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.common_delete)) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_delete)) } },
    shape = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).shape,
    containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
)
```

`TidiloTheme` provides one `OverlayBlurHost`. MainActivity passes the saved `AppConfig.overlayBlurEnabled` value into the theme, which resolves the effective setting. Do not read preferences or check theme identity in individual overlays. A standalone consumer without a host uses ordinary drawing.

The independent user preference defaults to true. Set `AppThemeStyle.overlayBlurPolicy` only when a preset requires another policy:

| Policy | Result | Settings switch |
| --- | --- | --- |
| `USER_CHOICE` (default) | Saved preference | Editable |
| `FORCE_ON` | Enabled | Checked, disabled, with explanation |
| `FORCE_OFF` | Disabled | Unchecked, disabled, with explanation |

Default and Modern use `USER_CHOICE`. Fixed policies never overwrite the saved choice; returning to a user-choice theme restores it. Theme authors do not implement Haze rendering or maintain separate blur palettes, but must still verify contrast in both modes with blur ON/OFF.

For a new native overlay family, follow the existing adapters:

1. Call `rememberOverlayBlurLayer(visible)` at a stable composition identity. Host-owned presentation order works across windows and composition siblings, including a sheet's confirmation. Keep the layer alive until the native closing animation ends; menus and the rail track native content presence for this reason.
2. Attach `Modifier.overlayBlurBackground(layer, actualShape, originalContainerColor)` at the complete native surface boundary, including padding, actions, and drag handles. Inspect the native API's modifier placement. A background on only the content slot is insufficient. Retain caller sizing, shape, anchor, state objects, scroll state, foreground colors, shadows, scrims, and accessibility semantics.
3. Pass transparent native fill and zero tonal elevation only while a non-null layer owns the whole background. With a null layer, retain original native drawing. Never apply opacity or own-content blur to foreground text and controls.
4. Reuse the shared renderer: external native shadow, shape-clipped opaque fallback base, captured lower-source blur, local translucent theme tint, 1dp outline, then sharp foreground. The completed overlay becomes a source for higher overlays, while each effect excludes itself and higher sources. Do not add full-screen blur or change the scrim.
5. Use explicit `HazeInput.Sources` and `ClearWhenUnavailable` across windows. Native backdrops cannot sample the activity behind a dialog or popup. Keep tint on the local surface. Do not enable experimental native backdrops, input sharing, or Glass.
6. Keep the host's root source attached across window changes. Haze records only when effects request input; OFF removes effect demand. Late source attachment can leave the first cross-window capture unavailable. No-host/software rendering uses ordinary surfaces; disappearing sources clear onto the opaque base. A rendering fallback never changes the saved preference.

Native anchored surfaces append movement, predictive-back/overscroll graphics layers, and clipping after the caller's modifier. The sheet and rail use `withNativeOverlayBackground` to keep those appended native elements outside the shared background when the final modifier chain is folded. This makes background and foreground inherit the same translation, scale, pivot, clipping, and RTL placement without recreating native animations or applying coordinate compensation. Use it only for native surface modifiers, not a content-slot modifier whose later padding must remain inside the background. Keep the folding-order unit test and the real native transform-order instrumentation test when changing this helper. Test short sheets, RTL rails, and moving/collapsing surfaces; no background or outline may remain on their scrim. Inspect the resolved Material3 version with dependency insight before relying on modifier placement.

Validate the new family with Default/Modern, light/dark, both saved choices and synthetic fixed policies. Check busy backgrounds, full-surface outline and clipped corners, sharp text, source updates/loss, OFF parity, nested/sibling windows, back/outside dismissal, IME/draft retention, narrow layouts, large text, RTL anchoring, disabled actions, and interrupted/repeated opening and closing without retained registrations. Use `HeaderTestHost` instrumentation on a hardware-accelerated device; resolver tests and browser mockups do not prove Haze rendering.

## Add blur to in-window screen chrome

`TidiloScreenScaffold` owns a stable `ScreenBackdropScope` and a unique body source key. Its header and `overlay` slot are foreground siblings. A separate chrome Haze graph captures the stationary `TidiloBackground` at z=0 and bodies at z=1; each input selects only the canvas and its owner's body key. This includes the real background behind transparent content while excluding other destinations and every chrome output. Library Search already uses the overlay slot. Every shared page header uses the scaffold, including loading and unavailable states. Keep separate keys for simultaneously composed navigation destinations.

Use `blurBackground = true` on `TidiloSurface` or `TidiloPrimaryActionSurface` only for a requested floating consumer. The default is false. The shared renderer replaces conflicting opaque fill while frost is active, retaining native click/ripple handling, theme decoration, shape and sharp content. Current opt-ins are Library Search, the Detail toolbar, and its compact browser-open action. A general PRIMARY_ACTION or TOOLBAR role alone does not enable blur.

For a custom background slot, consume `ChromeBlurBackground` within the same screen scope and keep foreground content outside that background. If a standalone screen needs a scope, wrap its body and chrome in one `ScreenBackdropScope` and attach `screenBackdropSource` only to the body. Use the scaffold's `bodyModifier` for background painting that must enter capture, such as Detail's ambient light; keep geometry and caller modifiers on the scaffold root. Do not allocate a source per button. No-host/software/OFF rendering must retain the ordinary theme surface; a missing captured source clears onto readable fallback paint.

Headers use the existing smootherstep stop table and draw-time fade geometry. A bounded background layer composites captured blur, fallback and tint before applying the mask exactly once; separately masking each paint would make the fade too opaque. The tail reaches transparency, without a hard bottom outline. Do not blur the title/action column or add a second effect to a sheet title row. A remembered ShaderBrush caches the actual draw size; do not send onSizeChanged values back into composition to build a first-frame mask. That introduces a delayed mask and an extra recomposition. Do not rebuild the brush for each scroll offset.

The app-wide modal graph remains separate and captures the composited body plus chrome. Never use that graph as an input for screen chrome: it contains the chrome's own output. Screen body sources remain attached across preference changes; Haze records on effect demand, and hidden/OFF consumers remove demand. Sharing source ownership does not guarantee one GPU replay across several effects. Keep Haze quality and experimental input-sharing defaults until profiling establishes a reason to change them.

Validate underlying-body motion, foreground isolation, source loss, concurrent destinations, live policy changes and state retention. Check both themes, light/dark, ON/OFF, fixed policies, RTL, large fonts and disabled controls. Run hardware rendering tests and compare frame measurements with an identical real-screen harness; see [chrome performance](chrome_blur_performance.md) for the measured method and limits.

## Verify a change

- Run `./gradlew testDebugUnitTest`. Use `AppThemeTest`, `AppConfigThemeTest`, `ThemeStringsTest`, `AppThemeStyleTest`, and `ThemeSurfaceResolverTest` for identity, persistence, labels, and resolved values. For a new resolver rule, check a representative role and the semantic state that could override it.
- Build the debug and instrumentation APKs with `./gradlew assembleDebug assembleDebugAndroidTest`. Follow the project emulator workflow to install the debug build with app data retained. AICore-dependent verification requires a supported physical device.
- Use `ThemeSurfaceParityTest` to capture the component catalogue and `ThemeSurfaceDrawingTest` and `ThemeConsumerRegressionTest` to check real rendering and consumer behavior. `ThemeSwitchLifecycleTest` covers persisted selection and state retention during theme changes. A resolver assertion checks values only. Inspect the rendered consumer when changing contrast, alpha, shape, or drawing.
- Check light and dark mode, selected and disabled states, content contrast, large text and narrow layouts, and overlays where the affected roles are used. For a visual preservation claim, capture before and after on the same device with the same theme, wallpaper, font scale, and fixture data. The catalogue produces captures for inspection. Pixel comparison and frame profiling are separate checks.


## Preserve adaptive screen geometry

Screen backgrounds and chrome capture sources stay full-window. Opt screen bodies into `TidiloScreenScaffold` system/cutout insets and pass its padding into lazy `contentPadding` or inside ordinary scrolling content. `bottomContentPadding` is app-only spacing; do not pass an already-inset value. Input bodies set `avoidIme = true` and leave keyboard padding to the scaffold. Its single `imePadding` owner avoids the lateral-transition narrowing observed with an IME ruler.

Apply `screenActionInsets` independently to floating controls. The shared header protects its foreground row while frost/gradient backgrounds remain full width. Detail artwork remains full bleed; only its text and controls receive safe margins. Native overlay windows keep Material3's own inset handling and must not inherit screen content padding. Verify Default/Modern, light/dark, both navigation modes, portrait/landscape, large text, IME show/hide, and blur ON/OFF without changing native overlay modifier order.
