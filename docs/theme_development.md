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

## Verify a change

- Run `./gradlew testDebugUnitTest`. Use `AppThemeTest`, `AppConfigThemeTest`, `ThemeStringsTest`, `AppThemeStyleTest`, and `ThemeSurfaceResolverTest` for identity, persistence, labels, and resolved values. For a new resolver rule, check a representative role and the semantic state that could override it.
- Build the debug and instrumentation APKs with `./gradlew assembleDebug assembleDebugAndroidTest`. Follow the project emulator workflow to install the debug build with app data retained. AICore-dependent verification requires a supported physical device.
- Use `ThemeSurfaceParityTest` to capture the component catalogue and `ThemeSurfaceDrawingTest` and `ThemeConsumerRegressionTest` to check real rendering and consumer behavior. `ThemeSwitchLifecycleTest` covers persisted selection and state retention during theme changes. A resolver assertion checks values only. Inspect the rendered consumer when changing contrast, alpha, shape, or drawing.
- Check light and dark mode, selected and disabled states, content contrast, large text and narrow layouts, and overlays where the affected roles are used. For a visual preservation claim, capture before and after on the same device with the same theme, wallpaper, font scale, and fixture data. The catalogue produces captures for inspection. Pixel comparison and frame profiling are separate checks.
