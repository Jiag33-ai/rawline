RAWLINE UI IMPLEMENTATION SPECIFICATION

1. Non-Negotiable Design Direction

You are implementing a high-density, professional, media-first dark interface with the visual and interaction character of a premium mobile photo editor.

THIS DESIGN DIRECTION IS LOCKED.

The application must feel:

- Professional and technical, not playful.
- Extremely dark, with the user's primary content carrying almost all colour.
- Compact and efficient, but never cramped.
- Flat rather than card-heavy.
- Precise rather than soft.
- Primarily black and charcoal, with one controlled blue accent.
- Designed around a large working canvas and subordinate controls.
- Touch-first on mobile.
- Fast and responsive, with deliberate but restrained motion.
- Visually closer to a professional creative tool than a conventional mobile settings app.
- Calm despite having many controls.
- Layered through black/charcoal surface differences rather than heavy shadows.
- Almost completely free of decorative styling.

Do not reinterpret this as:

- Material Design.
- Generic Android settings UI.
- Generic SaaS.
- shadcn styling.
- Apple-style floating glass panels.
- Glassmorphism.
- A rounded-card dashboard.
- A gradient-heavy UI.
- A colourful interface.
- An interface where every element is boxed.
- An interface with large headings and excessive whitespace.

The user's content or working media must visually dominate the application.

The UI should recede.

Core visual rules

- Primary canvas/background: pure black.
- Main control surfaces: approximately #1C1C1C.
- Raised control surfaces: approximately #202020 to #262626.
- Neutral selected surfaces: approximately #303030.
- Primary text: off-white, never pure glaring white everywhere.
- Secondary text/icons: neutral greys.
- Accent: a controlled medium Adobe-style blue, approximately #437EE4.
- Borders: thin and low contrast.
- Corners: mostly 0 to 6px.
- Shadows: rare.
- Typography: compact Roboto-like sans serif.
- Main editor controls: bottom-anchored on phones.
- Main media/content: maximum possible screen area.
- Motion: direct, short and physically coherent.
- No bounce.
- No decorative spring animation.
- No arbitrary fades between every interaction.
- No large floating cards unless functionality genuinely requires one.

Also distinguish application UI from device UI.

Do not recreate:

- Android clock.
- Battery indicators.
- Wi-Fi/mobile indicators.
- Android gesture/navigation buttons.
- Screen-recording indicators.
- Touch-position circles from screen recording.
- Any other operating-system chrome.

Use safe-area handling instead.

2. Overall Viewing Experience

The visual hierarchy must be:

1. Primary media/workspace
2. Current active tool
3. Current adjustment/value
4. Available neighbouring tools
5. Secondary navigation
6. Metadata and less important actions

The eye should immediately land on the content being edited, not the controls.

Canvas dominance

On an editing screen:

- The canvas is pure black.
- The asset has no border.
- The asset has no card background.
- The asset has no radius.
- The asset has no shadow.
- Fit the asset into available canvas space using contain.
- Preserve aspect ratio.
- Remaining area is black letterboxing.
- Controls should not unnecessarily cover important content.
- When a parameter panel opens, recompute the available canvas rather than simply obscuring half of the media.

Contrast hierarchy

Use approximately this progression:

- Canvas: #000000
- Deep shell: #0A0A0A
- Main dock/panel: #1C1C1C
- Slightly raised areas: #202020
- Floating menu: #262626
- Selected neutral tile: #303030
- Divider/border: roughly #393939
- Disabled material: roughly #676767
- Secondary icons/text: roughly #9A9A9A
- Primary icons/text: roughly #E7E7E7
- Important text: roughly #F2F2F2

Do not create depth by making every succeeding layer dramatically lighter.

Density

Set the system density to compact.

Typical visual targets:

- Utility toolbar height: 48 to 52px.
- Main control button: 40px.
- Icon touch target: 44px.
- Visible icon itself: 20 to 24px.
- Menu rows: 40px.
- Dense preset/list rows: 56 to 58px.
- Slider blocks: approximately 46px.
- Tool/category rail: approximately 64px.
- Compact master rail: approximately 54px.

On web, treat these numbers as CSS pixels.

On native mobile, treat them as dp.

3. Application Shell

3.1 Canonical mobile editor shell

The canonical phone layout should be constructed approximately as:

SAFE AREA / SYSTEM STATUS AREA
┌────────────────────────────────┐
│                                │
│       PRIMARY CANVAS           │
│       + overlay top bar        │
│                                │
│                                │
├────────────────────────────────┤
│ Parameter panel, when active   │
│ variable height                │
├────────────────────────────────┤
│ Category tool rail, 64px       │
├────────────────────────────────┤
│ Master compact rail, 54px      │
└────────────────────────────────┘
SAFE AREA / SYSTEM NAV AREA

When no deep tool panel is open:

SAFE AREA
┌────────────────────────────────┐
│                                │
│                                │
│       PRIMARY CANVAS           │
│                                │
│                                │
│      ┌──────────────────┐      │
│      │ Floating tool dock│     │
│      └──────────────────┘      │
└────────────────────────────────┘
SAFE AREA

Canvas

- Background: #000000.
- Occupies all remaining space.
- No outer page padding.
- Media positioned centrally.
- Top utility controls overlay the canvas instead of creating a large separate header.

Idle master tool dock

When no detailed adjustment surface is open:

- Width: calc(100% - 56px).
- Maximum sensible phone width: approximately 320px.
- Horizontal margin: approximately 28px.
- Height: 66px.
- Bottom offset: 8px above safe-area content boundary.
- Background: #1C1C1C.
- Radius: 4px.
- Shadow: none.
- Six primary tools can comfortably fit.
- Icon above label.
- Icons approximately 22px.
- Labels approximately 11px.
- Tool cells evenly distributed.

Do not turn this into a large rounded pill.

Expanded editing state

When a major editor mode is entered:

- Expand the master dock to full screen width.
- Remove side margins.
- Height becomes 54px.
- Remove labels from the master rail.
- Show the selected major mode using a blue approximately 42 × 42px tile.
- Place the detailed category rail directly above it.
- Place parameter controls directly above the category rail.
- Resize canvas space above these controls.

This state change should feel like the application moving from browsing tools into focused editing.

Top utility bar

Within the editor:

- Height: 52px.
- Transparent over the canvas.
- No large opaque header rectangle.
- Left edge: back button.
- Right edge: contextual actions such as undo, share and overflow.
- Horizontal padding: 6px.
- Icon touch target: 44px.
- Visible icon: 22px.
- Icon colour: #E8E8E8.
- Keep approximately 2–4px between adjacent hit targets.
- No text title unless the current workflow specifically requires one.
- Top bar remains stationary when bottom panels change.

If system APIs permit:

- Status bar background: black.
- Status-bar content: light.

4. Design Tokens

Implement these centrally. Do not scatter arbitrary visual constants through components.

4.1 Colours

--canvas: #000000;
--app-bg: #0A0A0A;

--surface-1: #1C1C1C;
--surface-2: #202020;
--surface-3: #262626;
--surface-selected: #303030;
--surface-hover: #292929;
--surface-pressed: #363636;

--border-subtle: rgba(255,255,255,0.07);
--border-default: rgba(255,255,255,0.13);
--border-strong: rgba(255,255,255,0.25);

--text-primary: #F2F2F2;
--text-secondary: #D2D2D2;
--text-muted: #9A9A9A;
--text-disabled: #686868;

--icon-primary: #E2E2E2;
--icon-secondary: #9E9E9E;
--icon-disabled: #666666;

--accent: #437EE4;
--accent-hover: #4D87EB;
--accent-pressed: #386FCB;
--accent-soft: rgba(67,126,228,0.18);

--success: #55A86A;
--warning: #D2A24A;
--error: #DF6464;
--info: #437EE4;

--focus-ring: #78A7FF;

--slider-track: #7A7A7A;
--slider-track-strong: #B6B6B6;
--slider-thumb: #EEEEEE;

--overlay: rgba(0,0,0,0.56);
--overlay-heavy: rgba(0,0,0,0.72);

--value-pill: rgba(20,20,20,0.94);

Colour restrictions

Do not add arbitrary purple, green, pink or orange accent colours.

Exceptions are allowed only when the colour itself communicates functionality, such as:

- Hue selection.
- Colour grading wheels.
- Temperature sliders.
- Warning/error/success states.
- Actual media.

Blue remains the sole application accent.

4.2 Functional colour gradients

Decorative gradients are prohibited.

Functional colour tracks are allowed.

Temperature

linear-gradient(
  90deg,
  #3C53CE 0%,
  #74799E 34%,
  #A5A19B 50%,
  #AFAA58 70%,
  #C4BE3F 100%
)

Tint

Use:

green -> neutral -> magenta

with restrained saturation.

Hue controls

Use the actual hue spectrum only where hue selection is functional.

4.3 Typography

Use:

font-family:
  "Roboto",
  system-ui,
  -apple-system,
  "Segoe UI",
  sans-serif;

Prefer actually bundling Roboto where possible so the UI does not drift into Inter-style geometry.

Do not substitute Inter unless bundling Roboto is impossible.

Typography scale

Screen/workflow title:
18px / 24px / 500

Major section title:
16px / 22px / 500

Panel title:
15px / 20px / 500

Standard body:
14px / 20px / 400

Control label:
14px / 18px / 400

Button:
14px / 18px / 500

Secondary:
13px / 18px / 400

Menu:
13px / 18px / 400

Bottom tool label:
11px / 14px / 400

Caption:
11px / 15px / 400

Tiny badge:
9px / 11px / 500

Typography behaviour

- Sentence case.
- Avoid all caps except established abbreviations such as B & W.
- Letter spacing: approximately 0.
- Use font-variant-numeric: tabular-nums for adjustment values.
- Do not use extremely bold headings.
- 600 weight should be rare.
- Never use giant 28–36px marketing-style titles inside the editor.
- Controls should look quiet enough that the image remains dominant.

4.4 Spacing

--space-1: 2px;
--space-2: 4px;
--space-3: 6px;
--space-4: 8px;
--space-5: 12px;
--space-6: 16px;
--space-7: 20px;
--space-8: 24px;
--space-9: 32px;
--space-10: 40px;

Common assignments

- Panel horizontal inset: 14–16px.
- Main text/list inset: 12–16px.
- Icon-to-label gap: 5px.
- Inline icon/text gap: 8px.
- Button internal icon gap: 7px.
- Menu icon/text gap: 10px.
- Adjacent compact buttons: 8px.
- Major panel groups: 16px.
- Small control groups: 8px.
- Slider blocks: no giant spacing between them.

Do not introduce one-off values like 19px, 27px, 31px without functional necessity.

4.5 Radii

--radius-none: 0px;
--radius-xs: 2px;
--radius-sm: 4px;
--radius-md: 6px;
--radius-lg: 8px;
--radius-pill: 999px;

Assignments:

- Main editor panels: 0px.
- Bottom parameter surfaces: 0px.
- Floating master dock: 4px.
- Neutral tool selection: 6px.
- Blue selected tool tile: 6px.
- Buttons: 4px.
- Inputs: 4px.
- Small dropdown/popover: 2–4px.
- Modal: 6px.
- Generic temporary bottom sheet: max 8px top corners.
- Status/value pill: full pill.
- Circular crop utilities: 999px.

Never use ubiquitous 12, 16, 20 or 24px SaaS radii.

4.6 Borders

--border-width: 1px;
--border-width-strong: 2px;

Use borders sparingly.

Suitable places:

- Function buttons.
- Selected asset/preset.
- Popover.
- Input.
- Slider thumb.
- Crop frame.
- Divider between major bottom rails.

Do not border:

- Every section.
- Every settings group.
- Every piece of metadata.
- Every navigation cell.

Open surfaces separated by spacing and background shifts are preferred.

4.7 Shadows

Cards and editor panels should generally use no shadow.

Use:

--shadow-menu:
  0 4px 12px rgba(0,0,0,0.46);

--shadow-modal:
  0 10px 30px rgba(0,0,0,0.52);

--shadow-drag:
  0 8px 24px rgba(0,0,0,0.50);

Do not give ordinary controls drop shadows.

4.8 Control heights

Top editor bar:             52px
Library header:             48px
Idle master dock:           66px
Compact master rail:        54px
Category rail:              64px
Panel tab bar:              44px
Main button:                40px
Small button:               34px
Icon hit target:            44px
Input/select:               40px
Menu row:                   40px
Preset/list row:            58px
Slider block:               46px
Global bottom navigation:   56px
Crop confirmation bar:      56px
Status pill:                28px
Toggle track:               32x18px

4.9 Icon sizes

Badge icon:       10–12px
Tiny metadata:    14px
Small utility:    16–18px
Menu icon:        18px
Standard:         20px
Top toolbar:      22px
Tool icon:        22–24px
Large utility:    26px

Use a single icon language.

Recommended:

- Material Symbols Outlined/Rounded at low-to-medium weight, or
- custom SVG equivalents matching those exact stroke proportions.

Use approximately:

stroke: 1.8–2px
round caps
round joins

Domain-specific icons that do not exist in the library should be custom SVGs made to match this same geometry.

Do not mix:

- Lucide stroke icons,
- filled Font Awesome icons,
- Material icons,
- emoji

inside the same toolbar.

4.10 Z-index

0     canvas/background
10    normal panel content
20    tool rails
30    editor top bar
40    crop overlays / canvas handles
50    dropdown/context menu
60    temporary sheet
70    scrim
80    modal/dialog
90    blocking save/loading overlay
100   toast/transient value label
110   tooltip

4.11 Motion tokens

--motion-instant: 80ms;
--motion-fast: 120ms;
--motion-normal: 180ms;
--motion-panel: 220ms;
--motion-page: 320ms;
--motion-shared: 420ms;
--motion-slow: 520ms;

--ease-standard: cubic-bezier(0.2, 0, 0, 1);
--ease-enter: cubic-bezier(0.16, 1, 0.3, 1);
--ease-exit: cubic-bezier(0.4, 0, 1, 1);
--ease-linear: linear;

Do not introduce bouncy springs into ordinary navigation.

4.12 Breakpoints

0–599px       phone
600–839px     large phone / small tablet
840–1199px    tablet / compact landscape
1200–1599px   desktop / laptop
1600px+       large desktop

Mobile is the canonical design.

Larger screens must adapt this design rather than inventing a separate SaaS UI.

5. Navigation

There are three distinct navigation levels.

5.1 Global navigation

For library/home-type views:

- Full-width bottom navigation.
- Height 56px.
- Background #1C1C1C.
- Fixed above safe-area inset.
- Approximately three to five destinations maximum.
- Icon centered above text.
- Icon 20–22px.
- Label 11px.
- Selected icon/label: accent blue.
- Inactive items: #BDBDBD.
- No selected pill behind each item.
- No exaggerated moving indicator.

If the actual app has different navigation destinations, map them into this treatment.

Do not copy irrelevant photo-editor destination names.

5.2 Primary editor tool navigation

In the normal editor state use a floating master dock.

Each item contains:

22px icon
5px gap
11px label

Examples of valid functional categories could be:

- Actions
- Presets
- Crop
- Edit
- Masking
- Remove

Map these to actual application functionality where necessary.

Selection

Idle:

- No giant active fill.
- Icons are off-white/grey.

When a deep mode is active:

- Master rail becomes full-width and compact.
- Labels disappear.
- Current master icon receives a 42 × 42px blue tile.
- Radius 6px.
- Icon remains white.

5.3 Editing category rail

Place above the compact master rail.

Examples:

- Auto
- Light
- Colour
- Blur
- Effects
- Detail
- Optics

Dimensions

- Height 64px.
- Background #1C1C1C.
- Horizontal scrolling allowed.
- Minimum category width approximately 50px.
- Gap approximately 2px.
- Icon 22px.
- Label 11px.

Selected state

Selected category gets a neutral tile:

width: approximately 42–46px
height: approximately 56px
background: #303030
radius: 6px

The category's icon and label remain light.

Do not make each category a colourful pill.

Premium/gated feature marker

If a real feature requires a premium marker:

- Blue circular badge: 12px.
- Position: overlap lower-right area of relevant icon.
- White star: approximately 7px.

Do not show this badge on ordinary features.

6. Main Content Layout

6.1 Editor canvas

The working asset is the visual centre of the application.

Implementation:

object-fit: contain;
object-position: center;
background: #000;

Do not place the asset inside a card.

When panels open

The panel must consume layout space.

Do not simply place an opaque panel over the bottom half of the media.

Sequence:

1. Preserve asset centre.
2. Recalculate available canvas bounds.
3. Animate the media from old fit bounds into new fit bounds.
4. Reveal panel content concurrently.
5. Leave top utility controls stationary.

Use a FLIP/shared-layout technique to avoid jumping.

6.2 Parameter tray

When a category is opened:

- Straight top edge.
- No drag handle unless the application actually supports dragging.
- No rounded upper corners.
- Background #202020 or #1C1C1C.
- Horizontal inset 14px.
- Variable height, generally 150–300px on phone.
- Internal vertical scrolling if required.
- Category rail remains fixed beneath it.
- Master rail remains fixed beneath category rail.

Do not allow the parameter panel to push critical bottom controls off-screen.

6.3 Open section philosophy

Adjustment controls should generally appear directly on the panel surface.

Do not wrap every subsection in a card.

Correct:

Exposure            0.00
────────────○──────────

Contrast                0
────────────○──────────

Highlights              0
────────────○──────────

Incorrect:

┌ Rounded card ┐
│ Exposure     │
│ slider       │
└──────────────┘

┌ Rounded card ┐
│ Contrast     │
...

6.4 Library/gallery view

For content-browsing surfaces:

- Background primarily black.
- Compact header.
- Use date/category grouping rather than giant section cards.
- Small heading on left.
- Count/action on right.
- Dense thumbnail grid.
- Approximately 2–3px gutter between thumbnails.
- Thumbnail corners square or maximum 2px.
- No shadows.
- No decorative card frames.
- Selected content may receive a 2px accent blue outline.

If there are dates/groups:

- Heading around 12px.
- Small count aligned right.
- Chevron for collapsible groups if appropriate.

7. Component Specifications

7.1 EditorTopBar

height: 52px
position: overlay on canvas
background: transparent
horizontal padding: 6px

Left:

- Back icon.
- 44px target.

Right:

- Undo when applicable.
- Share/export.
- Overflow.
- Each 44px target.
- Visible icons 22px.

Do not give each top-bar icon a permanent circular background.

Pressed state may briefly show a low-contrast square/rounded touch fill.

7.2 MasterToolDock

Idle state:

height: 66px
side margin: 28px
background: #1C1C1C
radius: 4px

Items evenly spaced.

No shadow.

Expanded state morphs into MasterToolRail.

7.3 MasterToolRail

Focused editing state:

height: 54px
width: 100%
background: #1C1C1C
border-top: 1px solid rgba(255,255,255,.10)

Icon-only.

Active item:

42x42px
background: #437EE4
radius: 6px

7.4 CategoryRail

height: 64px
background: #1C1C1C
overflow-x: auto
scrollbar: hidden

Selected category uses neutral #303030, not blue.

This distinction is important:

- Major active mode = blue.
- Minor category selection = neutral charcoal.

7.5 SliderControl

This is one of the most important components.

Structure:

Label                                Value

────────────────○────────────────────

Dimensions

- Total block: approximately 46px.
- Label: 14px.
- Value: 13px, tabular numerals.
- Horizontal track inset: 0.
- Track height: approximately 1px.
- Thumb diameter: 18px.
- Thumb visible ring: 2px.
- Thumb centre: same colour as panel.
- Invisible touch area: at least 44px high.

Track

Default:

- Grey #7A7A7A.
- Active/fill region can use #B6B6B6.
- Do not make ordinary tonal sliders blue.

Dragging

Dragging must:

- Update value continuously.
- Update main content continuously.
- Never lag behind the finger.
- Avoid easing/filter interpolation during drag.
- Show a temporary value pill near the top-centre of the canvas.

Example:

Shadows: +80

Value pill:

- Height approximately 24px.
- Horizontal padding 10px.
- Background rgba(20,20,20,.94).
- Text 11–12px.
- Radius full pill.
- Appears in 80ms.
- Remains visible while pointer/finger is down.
- Fades after release.

Do not reproduce screen-recording touch indicators.

7.6 TemperatureSlider

Same geometry as normal slider.

Track uses functional cool-to-warm gradient.

Thumb remains neutral white.

Never use blue application accent as the value fill.

7.7 ToggleRow

Layout:

Label                           Toggle

- Row height: 44px.
- Label: 13–14px.
- Toggle dimensions: 32 × 18px.
- Off background: #4A4A4A.
- On background: accent blue.
- Thumb: 14px.
- Thumb: off-white.
- Internal inset: 2px.

Do not use an oversized iOS switch.

7.8 FunctionButton

Used for controls such as:

- Curve
- B & W
- Grading
- Mix

Style:

height: 34–40px
padding-inline: 12px
background: transparent / #181818
border: 1px solid #515151
radius: 4px
font: 14px 500

Icon:

- 18px.
- Gap 7px.

Pressed:

- Background #2E2E2E.

These should feel like technical controls, not CTAs.

7.9 PrimaryButton

Only when an actual primary action requires text.

height: 40px
background: #437EE4
radius: 4px
padding-inline: 16px
text: #FFF
font: 14px / 500

No shadow.

Avoid using large blue primary buttons throughout the editor.

7.10 SecondaryButton

height: 40px
background: transparent
border: 1px solid #555
radius: 4px
text: #E9E9E9

7.11 Ghost/IconButton

- Hit area 44 × 44px.
- Visible icon 20–22px.
- Transparent default.
- Hover desktop: #292929.
- Press: #363636.
- Radius 4–6px.

No permanent border.

7.12 PanelTabs

For groups such as:

Effects     Vignette     Grain

- Height 44px.
- Text 14–15px.
- Inactive text #D0D0D0.
- Active text #F2F2F2.
- Active underline: 2px.
- Underline: off-white.
- Horizontal inset approximately 14px.

Do not use filled segmented pills unless the function-button pattern requires it.

7.13 Colour swatch selector

For channel/hue selection:

- Circular swatches approximately 12–14px.
- Gap approximately 10px.
- Selected state uses an outer light ring.
- Colour itself may represent hue.
- No large card container.

7.14 Colour wheel

For colour grading:

- Diameter approximately 145–160px on phone.
- Full spectrum wheel.
- Neutral selection centre marker.
- Small external luminance/saturation controls if needed.
- Keep surrounding panel black/charcoal.
- Wheel is the only vivid component in its area.

Colour grading mode should replace the existing colour parameter surface, not open a generic modal.

7.15 ToneCurveEditor

When the curve editor is opened:

- Main image/canvas remains visible.
- Overlay a fine graph grid directly over the image.
- Grid lines: white at approximately 12–16% opacity.
- Curve line: 1–1.5px off-white.
- Control points: approximately 8–10px.
- Active control point: light-filled with visible outline.
- Bottom channel controls:
  - master white,
  - red,
  - green,
  - blue.
- Channel selectors small and circular.

Bottom region:

Curve                               DONE

○    ○    ○    ○    channel controls

DONE should be a compact quiet button, not a giant CTA.

Enter behaviour

The normal sliders fade away while the graph overlays the canvas.

Do not open the curve editor as a generic centred modal.

7.16 CropWorkspace

Crop should be a dedicated mode.

Background:

#000000

Top area:

- Centre status pill such as current aspect mode.
- Example size: approximately 80 × 28px.
- Background #202020.
- Full pill radius.
- Help icon at upper-right if applicable.

Crop image

- Centre the media within large black working area.
- Outside crop region should darken appropriately.
- Crop rectangle remains visually stable while image rotates/moves beneath it.

Crop frame

- White lines.
- Thickness approximately 2px.
- Corner handles approximately 16px long.
- Side-middle handles approximately 24px long.
- Corners visually stronger than interior grid.
- Grid lines low opacity.

Rotation ruler

Place immediately below the image crop region.

- Curved/dotted tick scale.
- Main centre tick visually stronger.
- Numeric rotation centred, e.g. 0.00°.
- Small type approximately 11px.
- Live update during rotation.
- Zero point snaps subtly.

Circular crop utility buttons

Approximate:

44 × 44px
background: #2A2A2A
border: 1px solid #414141
radius: 50%
icon: 20px

Possible functions:

- auto/fit
- aspect lock
- rotate
- more

Do not use huge floating action buttons.

7.17 Crop options tray

Below crop canvas:

- Background #1C1C1C.
- Straight edges.
- Tabs approximately:

Aspect       Geometry

- Tab heading 16px.
- Active underline 2px.
- Premium badge only where genuinely applicable.

Aspect option row:

- Horizontally arranged tiles.
- Icon above text.
- Around 52–62px per option.
- Selected option has neutral #303030 rectangle.
- Radius 4px.

A small feature marker such as New may use:

- Accent blue.
- Tiny rectangle/ribbon.
- White 10–11px text.

Do not turn feature badges into large promotional banners.

7.18 RatioPopover

An anchored technical menu.

Examples:

☐ 10 × 16
☐ 9 × 16
☐ 8.5 × 11
☐ 5 × 7
☐ 4 × 5
☐ 3 × 4
☐ 2 × 3
☐ 1 × 2
☐ 1 × 1

Style:

- Width 110–130px.
- Background #252525.
- Radius 2px.
- Border subtle.
- Row height 30–32px.
- Text 11–12px.
- Checkbox approximately 14px.
- Very small shadow.
- Menu appears immediately adjacent to its trigger.

7.19 Crop confirmation bar

Bottom:

X             Crop and expand             ✓

- Height 56px.
- Background #1C1C1C.
- 1px upper divider.
- Centre title 16px, weight 500.
- Left and right icons approximately 24px.
- Icon touch targets 48px.

7.20 PresetBrowser

Preset mode uses a lower workspace panel rather than an isolated modal.

Possible top tabs:

Recommended   Premium   Yours

- Height approximately 44px.
- Small type.
- Active underline.
- Panel background #1C1C1C.

Entering a specific preset collection:

Header:

← User Presets

- Height 48px.
- Text 14px.

Preset row:

- Height 58px.
- Thumbnail 48 × 48px.
- Radius 2px.
- Gap 12px.
- Name 13px.
- Overflow button on right.
- No card around row.
- Divider optional and extremely subtle.

Unavailable thumbnail:

- Dark square.
- Fine border.
- Neutral circular placeholder graphic.
- No colourful generic illustration.

7.21 LibraryGrid

- Black surface.
- Dense.
- Group content by date or category.
- 4–6 columns depending width and thumbnail size.
- Tiny gutters.
- No masonry unless required by functionality.
- No card padding around groups.
- Selected media can receive accent outline.

If metadata badges are required:

- Place directly over thumbnail corners.
- 9–10px text/icons.
- Dark translucent background.
- Radius 2px.

7.22 Inputs

Because text inputs are not the dominant visual pattern, keep them compact.

height: 40px
background: #242424
border: 1px solid #454545
radius: 4px
padding-inline: 12px
font: 14px

Placeholder:

#838383

Focus:

border: #78A7FF
2px keyboard-only focus ring

Error:

border: #DF6464

Never create 52px-tall rounded pill inputs.

7.23 Search field

- Height 40px.
- Background #242424.
- Radius 4px.
- Search icon 18px.
- Text 14px.
- Clear icon at right when populated.
- Keep horizontal margins 12px.

7.24 Checkbox

- Visual square: 16px.
- Radius 2px.
- Border 1px.
- Selected: accent blue.
- Check: white.
- Hit target: at least 40px.

7.25 Radio

- Visual diameter: 16px.
- Selected inner dot approximately 8px.
- Accent blue when selected.
- Large invisible touch target.

7.26 Generic card

Cards are not a primary construction method.

If functionality genuinely requires one:

background: #202020
border: 1px solid rgba(255,255,255,.07)
radius: 4px
padding: 12–16px
shadow: none

Never automatically wrap every dashboard section in this component.

7.27 Data tables if required by the separate app

Use a dense technical table.

- Background: #1C1C1C.
- Header height: 40px.
- Row height: 40px.
- Horizontal cell padding: 12px.
- Text 12–13px.
- Header weight 500.
- Header colour secondary.
- 1px subtle horizontal dividers.
- No zebra striping.
- Hover: #252525.
- Selected: rgba(67,126,228,.12).
- Selected left/outline accent may use blue.
- Sticky header when vertically scrolling.
- Numeric cells right-aligned.
- Text cells left-aligned.
- Actions kept compact at far right.
- Horizontal overflow scrolls the data surface, not the entire page.

8. States

Every reusable interactive component must implement these states deliberately.

Default

- Quiet.
- Low contrast where possible.
- No unnecessary outlines.

Hover

Desktop/pointer only.

- Background may move one level lighter.
- Example transparent to #292929.
- Duration 120ms.
- No vertical lift.
- No dramatic shadow.

Pressed

- Background approximately #363636.
- Duration approximately 80ms.
- Filled buttons may scale to 0.985 for roughly 70ms.
- Toolbar/icon buttons should generally not scale.
- A restrained bounded touch/ripple overlay is acceptable on mobile:
  - white at approximately 10% opacity,
  - fade within about 220ms.

Do not use bouncy button physics.

Selected

Major tool:

- Accent blue tile.

Minor category:

- Neutral #303030 tile.

Tab:

- Light underline.

Selected asset:

- 2px blue outline.

Keep these different so hierarchy is clear.

Focused

Keyboard-only:

- 2px #78A7FF ring.
- Offset approximately 2px.
- Do not permanently show desktop-style focus outlines after ordinary touch interactions.

Disabled

- Content opacity approximately 0.38.
- Pointer interaction disabled.
- Maintain readable shape.
- Do not hide required unavailable controls completely.

Loading

Do not replace small controls with huge page spinners.

Use local indicator whenever possible.

Error

- Red reserved specifically for error.
- Keep text compact.
- Do not repaint entire surfaces bright red.

Success

- Quiet green status.
- Avoid large celebratory animation.

9. Motion and Animation System

Motion must be restrained but extremely polished.

The interface must never feel static, but it must also never feel playful.

9.1 General philosophy

Animate primarily:

- opacity
- transform
- shared layout transforms

Avoid continuous animation of:

- width
- height
- top
- left

unless required by native layout animation.

Use FLIP/layout animation where dimensions change.

The canvas should never flash or visibly reflow.

9.2 Pointer feedback

Icon button press

background:
transparent → rgba(255,255,255,.09)

duration:
80ms in

release:
120ms out

No bounce.

9.3 Master dock to focused editor transformation

When a major tool opens:

1. Floating dock expands horizontally from side-inset width to full width.
2. Dock height changes from 66px to 54px.
3. Dock labels fade from 1 → 0.
4. Category rail enters immediately above.
5. Parameter panel enters above category rail.
6. Canvas adjusts to its new available size.
7. Active major tool tile becomes blue.

Duration:

220ms

Ease:

cubic-bezier(0.2,0,0,1)

Panel contents:

opacity 0 → 1
translateY 8px → 0
180ms
ease-enter

Do not animate these pieces sequentially over a full second.

They should feel like one coherent state transformation.

9.4 Closing focused editor state

Reverse:

- Parameter content fades out in 100ms.
- Panel collapses.
- Category rail disappears.
- Master rail becomes inset floating dock.
- Labels fade back in.
- Canvas expands.

Total:

200–220ms

9.5 Category switching

When switching Light → Colour → Effects, etc.:

- Keep canvas stationary.
- Keep both bottom rails stationary.
- Crossfade parameter content.

Outgoing:

opacity 1 → 0
translateX 0 → -4px
100ms

Incoming:

opacity 0 → 1
translateX 4px → 0
140ms

Selected neutral tile background transitions over 120ms.

No large horizontal page swipe.

9.6 Tab switching

- Underline moves or crossfades in 140ms.
- Panel content crossfades in 120–160ms.
- Optional content movement maximum 4px.

Do not animate tabs with oversized pill movement.

9.7 Slider interaction

No easing on data while dragging.

The thumb position and preview must directly follow the input.

When drag begins:

Value pill:

opacity 0 → 1
scale .97 → 1
80ms
ease-enter

After release:

- Hold around 350ms.
- Fade out over 160ms.

Do not display any artificial grey pointer dot.

9.8 Dropdown enter

opacity: 0 → 1
translateY: -3px → 0
scale: .985 → 1
duration: 120ms
ease: ease-enter
transform-origin: trigger side

Exit:

opacity: 1 → 0
translateY: 0 → -2px
duration: 90ms
ease: ease-exit

9.9 Popover/context menu

Same as dropdown.

For a top-right overflow menu:

transform-origin: top right

No spring.

9.10 Preset panel

Enter:

translateY: 16px → 0
opacity: 0 → 1
180ms
ease-enter

Image/canvas fit should update concurrently.

Preset list contents may stagger by zero milliseconds.

Do not use item-by-item entrance animations.

9.11 Crop mode enter

The crop transition is a workspace transformation.

Sequence simultaneously:

- Existing bottom tool surfaces fade.
- Canvas background remains black.
- Image animates from current fitted bounds into smaller crop-workspace bounds.
- Crop frame fades in.
- Rotation ruler fades in.
- Crop option tray rises.
- Dedicated bottom confirmation bar replaces normal master navigation.

Timing:

image/layout: 220ms
controls: 180ms

Ease:

ease-standard

Do not abruptly teleport the image.

9.12 Tone curve enter

Existing adjustment surface:

opacity 1 → 0
100ms

Grid/curve overlay:

opacity 0 → 1
150ms

Curve panel:

opacity 0 → 1
translateY 6px → 0
180ms

Canvas stays in place.

9.13 Colour grading enter

Use approximately:

220–240ms

Old colour controls fade down.

Colour wheel panel:

opacity 0 → 1
translateY 8px → 0

Do not zoom the wheel dramatically.

9.14 Chrome hide/show

If tapping the canvas toggles distraction-free viewing:

Hide:

top controls opacity 1 → 0
bottom controls opacity 1 → 0
140ms

Canvas expands to freed space over 180ms.

Show:

opacity 0 → 1
160ms

No slide from off-screen unless the physical bar also needs to reclaim space.

9.15 Toggle

Thumb:

translateX(...)
140ms
ease-standard

Track colour:

140ms

No spring.

9.16 Spinner

- Stroke 2px.
- Diameter 24–32px.
- Rotation duration 700ms.
- Linear.
- Do not rotate giant logos.

10. Page and View Transitions

10.1 Library → detail/editor

This is a horizontal mobile navigation transition.

Incoming editor:

translateX: 100% → 0
duration: 300–340ms
ease: cubic-bezier(.2,0,0,1)

Outgoing library can move slightly:

translateX: 0 → -8%

while remaining visible beneath the incoming editor.

Do not fade the entire application to black.

The editor should visibly arrive from the right.

10.2 Editor → library

Reverse direction.

Where a source thumbnail exists, use a shared-media transition.

Recommended sequence after any required save completes:

1. Library is revealed beneath the editor.
2. Editor shell slides right.
3. The current media visually detaches from the full workspace.
4. It shrinks toward the corresponding destination thumbnail.
5. Destination thumbnail becomes the final visual object.

Shared element duration:

380–480ms

Recommended:

420ms

Ease:

cubic-bezier(.16,1,.3,1)

Match:

- position
- crop
- size
- aspect ratio

as continuously as possible.

If a destination thumbnail is off-screen, fall back to the normal reverse slide.

10.3 Editor internal modes

Do not perform full-page transitions between:

- Light
- Colour
- Blur
- Effects
- Detail
- Optics

The editor shell persists.

Only relevant lower controls change.

10.4 Full task subviews

For export/share or another substantial workflow:

- Open as a full editor subview rather than a tiny popover if content is complex.
- Use the same dark surfaces.
- Use back navigation at top-left.
- Enter from the right over 260–320ms.

11. Scrolling Behaviour

Canvas

Never scroll as part of page content.

Pan and zoom gestures belong to the media canvas itself.

Parameter panel

- Scroll vertically only if parameter content exceeds available panel height.
- Rails beneath stay fixed.
- Scrollbar width approximately 2px.
- Thumb #717171.
- Auto-hide when inactive.
- No visible track.

Tool/category rails

- Horizontal scroll.
- Hide horizontal scrollbar.
- Keep icons at their intended size rather than compressing them.
- Tapping an item should smoothly bring it into view if partially clipped.
- Scroll-to-visible duration around 180ms.

Preset list

- Vertical scroll.
- Header fixed if useful.
- No nested scrolling inside individual rows.

Gallery

- Main content is the single vertical scroll owner.
- Header and global bottom navigation remain fixed.
- Preserve scroll position after returning from editor.

Overscroll

Avoid colourful Android overscroll glow.

Use either:

- subtle platform stretch, or
- no decorative overscroll effect.

12. Loading Experience

12.1 Media opening

Preferred:

1. Immediately display cached/thumbnail-quality media.
2. Fit it correctly.
3. Load full-resolution asset.
4. Replace without changing geometry.

If no preview exists:

- Canvas stays black.
- Show a 28px spinner centred on working region.
- Spinner uses off-white or muted blue depending context.

Do not flash a white page.

12.2 Preset/tool data loading

Place a small spinner in the panel itself.

- Diameter 24–28px.
- Accent blue acceptable here.
- Centre it in available panel body.
- Existing editor chrome stays usable where safe.

12.3 Blocking save before navigation

For operations that must finish before leaving a screen, use a dedicated blocking state.

Dim the complete editor significantly but keep it visible.

Approximately:

content perceived opacity: 35–45%

On the canvas:

32px circular spinner
small centred status text underneath

Example pattern:

          ○

   Saving your edits…

Text:

- 14px.
- White.
- Centre aligned.

Do not place this message in a giant modal.

After completion, immediately continue the requested navigation transition.

12.4 Skeletons

Use skeletons only for list/data layouts where geometry is known.

Skeleton colour:

base: #242424
highlight: #303030

Animation:

1200ms linear

Subtle.

For media editing tasks, prefer real thumbnail placeholders or spinners instead.

12.5 Optimistic updates

For local lightweight adjustments:

- Update immediately.
- Save asynchronously.
- Do not block each slider change with a spinner.

Only show blocking state when navigation/data safety truly requires it.

13. Modals, Menus and Overlays

13.1 Overflow menu

Anchored to triggering icon, normally upper-right.

Dimensions:

width: 165–175px
background: #262626
radius: 3px
border: 1px solid rgba(255,255,255,.10)
shadow: 0 4px 12px rgba(0,0,0,.46)

Rows:

height: 40px
padding: 10–12px
icon: 18px
text: 13px
gap: 10px

Possible pattern:

↓ Save copy
◉ View options
◌ Create preset
⌘ Copy settings
   Paste settings       disabled
↶ Apply from previous
ⓘ Help
────────────
● Profile

Disabled row:

- Text/icon #686868.
- No interaction.

Avoid huge vertical padding.

13.2 Tooltips

Desktop:

- Delay: 500ms.
- Exit: 80ms.
- Background: #2B2B2B.
- Text 11px.
- Radius 4px.
- Padding 6px 8px.
- Shadow subtle.

Touch:

- Use long press only where clarification is genuinely necessary.

13.3 Modal

Only for true decisions or confirmations.

width: min(320px, viewport - 32px)
max-height: min(560px, viewport - 48px)
background: #242424
radius: 6px
border: subtle
shadow: modal shadow

Scrim:

rgba(0,0,0,.56)

Header:

- 16px/500.
- Padding 18px.

Body:

- 14px.
- Padding 18px.

Footer:

- Compact.
- Right-aligned actions.
- 12–16px padding.

Enter:

opacity 0 → 1
scale .985 → 1
translateY 4px → 0
180ms

Exit:

opacity 1 → 0
scale 1 → .99
120ms

13.4 Temporary bottom sheet

Use only where a bottom sheet makes functional sense.

- Background #202020.
- Top radii max 8px.
- Do not use oversized 24–32px modern sheet radii.
- Scrim about 50%.
- Handle only if draggable.
- Enter from bottom over 220ms.

Editor parameter panels are not generic bottom sheets and should remain square-edged.

13.5 Share/export workspace

For a complex share flow:

- Use a dark full-screen subview.
- Header approximately 48px.
- Back left.
- Title near left.
- Small secondary action right such as Select all.

Media strip:

- Horizontal.
- Selected item receives 2px accent outline.
- Small checkbox indicator.

Quick actions:

- Compact icons with labels beneath.
- Keep spacing efficient.

Additional share options:

- Dense list rows.
- Icon on left.
- Title.
- Optional short supporting text.
- No card around each row.

14. Empty, Success, Warning and Error States

Empty state

Do not use colourful illustrations.

Use:

- A monochrome 28–36px icon.
- Heading 15px / 500.
- Supporting text 13px.
- One compact CTA when appropriate.
- Centre within available panel or content area.
- Max text width around 280px.

Colours:

- Heading primary.
- Body muted.
- Icon secondary.

Keep the black/charcoal background.

Inline error

Place close to affected control.

- Text 12px.
- Error colour.
- 4px separation from control.
- Avoid full red panel backgrounds.

Error banner

If a larger error impacts the workspace:

background: rgba(223,100,100,.12)
border-left: 2px solid #DF6464
padding: 10–12px

Heading/body stay compact.

Persistent until resolved/dismissed.

Warning

Use subdued amber.

Do not use bright yellow across a huge area.

Success

For ordinary saves/actions:

- Quiet toast.
- Optional check icon.
- Auto-dismiss after 2.5s.

Do not show confetti.

Destructive confirmation

Require explicit confirmation for irreversible operations.

- Dialog wording short and specific.
- Cancel is neutral.
- Destructive confirm uses error colour.
- Default focus should not accidentally land on destructive action on desktop.

Toasts

Position:

- Mobile: horizontally centred above current bottom navigation/dock.
- Offset: 12–16px above dock.
- Desktop: lower centre or upper-right depending context, but remain consistent.

Style:

min-height: 36px
max-width: 320px
background: #292929
border: subtle
radius: 6px
padding: 8px 12px
font: 13px

Maximum visible:

3

Normal duration:

2500–3500ms

Errors should remain longer or require dismissal.

Enter:

opacity 0 → 1
translateY 6px → 0
160ms

Exit:

opacity 1 → 0
120ms

15. Responsive Behaviour

Mobile is the source of truth.

Do not turn larger layouts into a generic web dashboard.

15.1 Phone: 0–599px

Use the canonical layout described above.

- No permanent sidebar.
- Canvas central.
- Floating idle master tool dock.
- Full-width compact rails when editing.
- Parameter panel at bottom.
- Overflow categories scroll horizontally.
- Crop uses full dedicated screen.
- Dialog width almost screen width minus 32px.
- Touch targets minimum 44px.
- Preserve safe-area insets.

At very narrow widths:

- Do not shrink icons.
- Allow tool rails to scroll.
- Shorten labels only if a recognised abbreviation exists.

15.2 Large phone / small tablet: 600–839px

Maintain mobile hierarchy.

Differences:

- Canvas gains more breathing room.
- Limit parameter panel content width where practical.
- Master floating dock max width around 420px.
- Category rail may centre contents when all fit.
- Preset grid/list can display additional columns.
- Dialog max width remains compact.

Do not stretch slider controls across 700px unless still bottom-oriented.

15.3 Tablet / landscape: 840–1199px

Recompose rather than merely enlarging.

Recommended shell:

┌──────────────────────────────────────────────┐
│ Top utility bar                              │
├──────┬───────────────────────────┬───────────┤
│ tool │                           │ inspector │
│ rail │        CANVAS             │  panel    │
│      │                           │           │
└──────┴───────────────────────────┴───────────┘

- Left major-tool rail: about 64px.
- Canvas consumes centre.
- Inspector width approximately 300–320px.
- Inspector background #1C1C1C.
- Category controls appear at top of inspector.
- Slider width limited to inspector.
- No floating cards.
- Top utility bar remains 52px.

Portrait tablets may retain bottom-oriented controls if the working asset benefits from width.

15.4 Desktop/laptop: 1200–1599px

Use professional creative-tool composition.

52px top bar

64–72px left tool rail
centre black canvas
320–340px right inspector

- App background remains near black.
- No large dashboard shell.
- Inspector full height below top toolbar.
- Right panel vertical scroll owns its own controls.
- Canvas itself does not page-scroll.
- Gallery/library can switch to denser 6–8 column layouts.
- Hover states become active.
- Tooltips become available.
- Keyboard focus required.

15.5 Large desktop: 1600px+

Do not simply make controls huge.

- Left rail max 72px.
- Inspector max approximately 360px.
- Centre canvas receives additional space.
- Control sizes stay nearly identical.
- Slider widths inside inspector stay bounded.
- Library content may use more columns.
- Dialog max widths remain controlled.

Visual density stays compact.

16. Accessibility

Preserve the visual system while meeting accessibility requirements.

Touch targets

Minimum interactive area:

44 × 44px

The visible icon may remain 20–24px.

Keyboard

All desktop/web interactions must support:

- Tab navigation.
- Shift+Tab.
- Enter/Space activation.
- Escape to dismiss overlays.
- Arrow keys for menu navigation.
- Arrow keys for sliders where appropriate.

Focus

Use :focus-visible.

Ring:

2px #78A7FF
2px offset

Do not rely on hover.

Contrast

Primary text must comfortably exceed AA contrast against dark backgrounds.

Muted information may be lower contrast only when not essential.

Sliders

Provide:

- Accessible label.
- Current value.
- Minimum.
- Maximum.
- Keyboard increments.
- Optional larger increment with Shift where useful.

Colour controls

Do not make colour alone the sole indicator.

Include:

- labels,
- values,
- selected outline,
- accessible textual description.

Dialogs

- Trap focus.
- Restore focus to trigger after closing.
- Escape closes unless destructive operation is already being committed.

Reduced motion

When:

prefers-reduced-motion: reduce

Change:

- Page slides to 100–140ms fades.
- Shared-element transitions to simple short fade.
- Panel transformations to minimal fades.
- Remove scaling.
- Retain immediate slider feedback.
- Spinner can remain where necessary.

Do not disable essential feedback.

17. Component Inventory

Build these as reusable primitives rather than styling every page separately.

Application structure

- AppShell
  - Owns safe areas, black background and navigation state.

- EditorShell
  - Coordinates canvas, top bar and lower control surfaces.

- MediaCanvas
  - Black contain-fit workspace with zoom/pan support.

- EditorTopBar
  - Transparent contextual top actions.

- LibraryShell
  - Dense black content browser.

Navigation

- GlobalBottomNav
  - App-level destinations.

- FloatingMasterDock
  - Labelled idle editor tools.

- CompactMasterRail
  - Icon-only focused editor rail.

- MasterToolItem
  - Major tool state.

- CategoryRail
  - Horizontal adjustment categories.

- CategoryToolItem
  - Icon + label neutral selection.

- PremiumBadge
  - Tiny blue star marker.

Panels

- ParameterTray
  - Square-edged lower editing surface.

- PanelTabs
  - Underlined mode tabs.

- PanelHeader
  - Compact title/action area.

- SettingsSection
  - Open section, not card by default.

Editing controls

- AdjustmentSlider
  - Label, value and technical slider.

- GradientSlider
  - Functional colour gradient.

- ToggleRow
  - Label with compact switch.

- FunctionButton
  - Outlined mode button.

- ColourChannelSelector

- ColourWheel

- ToneCurveEditor

- ValueFeedbackPill

Crop

- CropWorkspace

- CropFrame

- CropHandle

- CropRotationRuler

- CropUtilityButton

- CropOptionsTray

- AspectOptionTile

- RatioPopover

- CropConfirmationBar

Presets/content

- PresetBrowser

- PresetCollectionHeader

- PresetRow

- MediaGrid

- MediaThumbnail

- DateGroupHeader

Generic controls

- Button

- IconButton

- Input

- SearchInput

- Select

- Checkbox

- Radio

- Toggle

- Dropdown

- ContextMenu

- Tooltip

Feedback

- Spinner

- LocalLoader

- BlockingSaveOverlay

- Skeleton

- Toast

- ErrorBanner

- EmptyState

Overlay/task views

- Modal

- TemporaryBottomSheet

- ShareWorkspace

- ConfirmationDialog

Every new component must derive from these visual primitives.

18. Implementation Rules

Architecture

Use a central theme/token object.

For web:

- CSS custom properties.
- CSS Grid/Flexbox.
- GPU-friendly transforms.
- Floating UI or Radix only for behaviour if needed.
- Override all visual defaults.

For React Native:

- Central theme constants.
- Reanimated/layout transitions where useful.
- Native safe-area primitives.
- Virtualised lists for large media/preset libraries.

For another framework, preserve the same architecture.

Layout

Prefer:

shell
  ├ canvas flex:1
  └ control region auto

rather than absolute-positioning every panel.

Use absolute positioning primarily for:

- top canvas controls,
- crop overlays,
- floating value labels,
- menus,
- genuine overlays.

Performance

- Virtualise large thumbnail lists.
- Decode appropriately sized image previews.
- Do not render original full-resolution images for gallery thumbnails.
- Debounce expensive persistence, not visible slider response.
- Use transforms for movement.
- Avoid huge live backdrop filters.
- Avoid heavy box-shadow stacks.
- Keep colour/preview processing off the main UI thread where architecture permits.

Libraries

Behaviour libraries may be used for:

- accessibility,
- focus management,
- floating menu placement,
- gestures,
- motion.

Their default visual appearance must be removed.

Do not allow:

- Material default cards,
- Material default oversized ripples,
- Radix/shadcn default radii,
- Bootstrap button styling,
- native HTML default controls

to remain visible.

Functional adaptation

Do not blindly reproduce photo-editor labels if the separate application uses other functionality.

Transfer:

- hierarchy,
- density,
- tool navigation,
- panel construction,
- button language,
- colour system,
- typography,
- motion,
- interaction patterns.

Example:

If the app has a property editor rather than photo adjustments, the property editor should still use:

- compact open controls,
- dark bottom/right panel,
- neutral selected categories,
- blue major-mode selection,
- 46px control rows,
- subdued borders.

Things you MUST NOT change

Do not:

- Change the accent palette.
- Add multiple brand accent colours.
- Increase global radius.
- Turn lower panels into floating rounded sheets.
- Add gradients except functional colour controls.
- Add glass blur.
- Add frosted transparent cards.
- Add giant shadows.
- Make every section a card.
- Add large headings to editor screens.
- Replace compact tool rails with generic tab bars.
- Replace black canvas with dark grey.
- Make controls more spacious because it looks “modern”.
- Add spring/bounce animation.
- Add hover elevation to cards.
- Centre every piece of content.
- Create giant CTA buttons.
- Redesign the navigation purely for aesthetic preference.
- Draw fake operating-system status/navigation UI.
- Draw recording/touch indicators.

19. Visual QA Checklist

After implementation, inspect the finished application against every item below.

Global visual direction

- [ ] Canvas is genuinely black, not dark navy.
- [ ] Main panels are around #1C1C1C.
- [ ] Raised surfaces differ subtly rather than dramatically.
- [ ] Accent blue is approximately #437EE4.
- [ ] No unintended secondary accent palette exists.
- [ ] Media/content is visually dominant.
- [ ] UI feels technical and professional.
- [ ] UI density is compact.
- [ ] No unnecessary decorative effects have been introduced.

Typography

- [ ] Roboto or the defined fallback is consistently used.
- [ ] There is no accidental Inter/shadcn appearance.
- [ ] Control labels are approximately 14px.
- [ ] Bottom tool labels are approximately 11px.
- [ ] Heading weights are restrained.
- [ ] There are no giant editor headings.
- [ ] Adjustment numbers use tabular numerals.
- [ ] Sentence case is used consistently.

Canvas

- [ ] Canvas has zero radius.
- [ ] Canvas has zero shadow.
- [ ] Media uses contain behaviour.
- [ ] Media remains centred.
- [ ] Black letterboxing is correct.
- [ ] Canvas does not scroll with parameter content.
- [ ] Canvas smoothly refits when control areas open.
- [ ] Canvas does not visibly jump during panel changes.

Top bar

- [ ] Top bar is approximately 52px.
- [ ] It overlays the canvas.
- [ ] It does not look like a conventional opaque app header.
- [ ] Back action is left aligned.
- [ ] Context actions are right aligned.
- [ ] Visible icons are approximately 22px.
- [ ] Hit targets are at least 44px.
- [ ] No permanent circles exist behind ordinary icons.

Floating master dock

- [ ] Idle dock is inset from both sides.
- [ ] Dock height is approximately 66px.
- [ ] Dock background is #1C1C1C.
- [ ] Dock radius is only about 4px.
- [ ] Dock contains icon-over-label tools.
- [ ] Dock has no heavy shadow.
- [ ] Dock does not resemble a large rounded pill.

Focused editor rail

- [ ] Rail becomes full width.
- [ ] Labels disappear from the master rail.
- [ ] Rail height is approximately 54px.
- [ ] Selected major mode uses a blue approximately 42px tile.
- [ ] Tile radius is approximately 6px.
- [ ] Minor category selection remains neutral grey instead of blue.

Category rail

- [ ] Category rail is approximately 64px.
- [ ] Categories contain icon and short label.
- [ ] Rail horizontally scrolls when necessary.
- [ ] Scrollbar is hidden.
- [ ] Selected category gets a #303030 tile.
- [ ] Gated features use only tiny badges.

Parameter panel

- [ ] Parameter surface has a straight top edge.
- [ ] It is not styled as a rounded bottom sheet.
- [ ] Horizontal padding is around 14–16px.
- [ ] Controls are placed openly on the panel.
- [ ] Sections are not all wrapped in cards.
- [ ] Tall content scrolls independently.
- [ ] Category/master rails remain fixed.

Sliders

- [ ] Slider track is thin.
- [ ] Thumb is approximately 18px.
- [ ] Thumb uses light ring styling.
- [ ] Label is left aligned.
- [ ] Current value is right aligned.
- [ ] Drag interaction updates in real time.
- [ ] No blue fill is used on ordinary tonal sliders.
- [ ] Temperature/tint tracks use functional gradients only.
- [ ] Finger/pointer drag shows compact temporary value feedback.
- [ ] No recording touch-dot graphic was accidentally recreated.

Buttons

- [ ] Standard controls are approximately 40px high.
- [ ] Corners are around 4px.
- [ ] Buttons have no unnecessary shadows.
- [ ] Outlined function buttons use fine neutral borders.
- [ ] Giant blue CTAs have not proliferated.
- [ ] Press feedback is short and restrained.

Icons

- [ ] One coherent icon family is used.
- [ ] Standard icons are 20–24px.
- [ ] Stroke geometry is consistent.
- [ ] Custom icons visually match library icons.
- [ ] No emoji are used as interface icons.
- [ ] Active/inactive colours are consistent.

Borders

- [ ] Most borders are 1px.
- [ ] Subtle borders are approximately 7–13% white.
- [ ] Every surface is not unnecessarily outlined.
- [ ] Dividers are visible but subdued.

Radii

- [ ] Standard control radius is approximately 4px.
- [ ] Selected tiles are approximately 6px.
- [ ] Panels are square-edged.
- [ ] No random 16–24px SaaS radii exist.
- [ ] Pill radius is used only for genuine pills.
- [ ] Circular utilities remain circular.

Shadows

- [ ] Standard cards/panels have no shadow.
- [ ] Menus use only restrained elevation.
- [ ] Modals use stronger elevation only when needed.
- [ ] Hover does not create floating card effects.

Presets/lists

- [ ] Rows are approximately 58px.
- [ ] Thumbnails are approximately 48px.
- [ ] Thumbnails have very small radius.
- [ ] List rows are not individual cards.
- [ ] Overflow action sits at row end.
- [ ] Loading occurs inside the panel instead of blanking the app.

Crop interface

- [ ] Crop workspace uses pure black.
- [ ] Media sits centrally.
- [ ] Crop frame uses clean white handles.
- [ ] Rotation ruler is compact.
- [ ] Utility controls are approximately 44px circles.
- [ ] Crop lower panel remains square-edged.
- [ ] Aspect tabs use underline hierarchy.
- [ ] Selected aspect option uses neutral grey.
- [ ] Ratio menu is narrow and dense.
- [ ] Confirmation bar is approximately 56px.
- [ ] X and check controls are clearly separated.

Tone curve

- [ ] Curve grid overlays media rather than opening a generic modal.
- [ ] Grid is subtle.
- [ ] Curve is fine and light.
- [ ] Channel selectors are compact.
- [ ] Mode transition preserves the canvas.

Menus

- [ ] Overflow menus open beside their trigger.
- [ ] Width is approximately 165–175px.
- [ ] Menu rows are approximately 40px.
- [ ] Background is around #262626.
- [ ] Radius is low.
- [ ] Shadow is restrained.
- [ ] Disabled items are visibly muted.
- [ ] Menu opening has no bounce.

Loading

- [ ] Full editor does not become a blank screen unnecessarily.
- [ ] Media preview appears quickly.
- [ ] Local loading uses local spinners.
- [ ] Save-before-exit can use a blocking dim overlay.
- [ ] Blocking save includes spinner and concise status text.
- [ ] Tiny operations do not trigger giant loading overlays.

Motion

- [ ] Fast feedback is around 80–120ms.
- [ ] Normal control transitions are around 180ms.
- [ ] Panels use approximately 220ms.
- [ ] Full page movement is approximately 300–340ms.
- [ ] Shared media transition is approximately 420ms.
- [ ] Standard easing matches the defined curves.
- [ ] No bounce or overshoot exists.
- [ ] No gratuitous scaling exists.
- [ ] Slider preview has zero perceptible lag.
- [ ] Panel transitions preserve spatial continuity.

Page navigation

- [ ] Editor enters from the right on mobile.
- [ ] Underlying library remains spatially understandable.
- [ ] Back navigation reverses the direction.
- [ ] Shared media return animation is used where technically practical.
- [ ] Internal categories do not become full-page transitions.
- [ ] Scroll position in library is preserved.

Responsive behaviour

- [ ] Phone uses bottom-oriented canonical editor layout.
- [ ] Narrow widths scroll tools instead of shrinking them.
- [ ] Tablet recomposes controls rather than simply scaling.
- [ ] Desktop keeps a central black canvas.
- [ ] Desktop inspector remains narrow and technical.
- [ ] Large desktop does not make buttons gigantic.
- [ ] Media remains the largest visual element.

Accessibility

- [ ] Interactive hit areas are minimum 44px.
- [ ] Text contrast is sufficient.
- [ ] Focus-visible states exist.
- [ ] Sliders expose accessible values.
- [ ] Menus support keyboard interaction.
- [ ] Modals manage focus properly.
- [ ] Colour is not the only state indicator.
- [ ] Reduced-motion behaviour exists.

Library defaults

- [ ] No default browser buttons remain visible.
- [ ] No default HTML range slider styling remains.
- [ ] No unmodified Material component styling remains.
- [ ] No unmodified shadcn styling remains.
- [ ] No platform-default white select menu leaks into dark mode.
- [ ] No inconsistent icon family appears.

Final consistency

- [ ] Spacing comes from the token system.
- [ ] Radius comes from the token system.
- [ ] Motion comes from the token system.
- [ ] Colours come from the token system.
- [ ] No screen invents its own design language.
- [ ] New components clearly belong to the same interface.
- [ ] No unnecessary gradients exist.
- [ ] No glassmorphism exists.
- [ ] No excessive animation exists.
- [ ] No fake OS status/navigation elements exist.
- [ ] Content remains the visual priority.

20. Final Lock

THE DESIGN SYSTEM ABOVE IS THE APPROVED AND LOCKED VISUAL DIRECTION.

Implement it consistently across the application.

Your role is now primarily IMPLEMENTATION, not visual redesign.

Do not:

- Reinterpret the palette.
- Replace it with library defaults.
- Modernise it into rounded SaaS styling.
- Make it more colourful.
- Make it more spacious.
- Remove the compact tool hierarchy.
- Replace black canvas areas with generic grey cards.
- Change the blue accent.
- Introduce glassmorphism.
- Introduce decorative gradients.
- Add unnecessary shadows.
- Increase radii.
- Add bounce/spring animation.
- Simplify away important motion.
- Add animation that is not described.
- Replace persistent editor regions with arbitrary page layouts.
- Reorganise major interaction areas purely because another layout seems easier to code.

If a technical limitation requires an adjustment, preserve the closest possible visual and behavioural result.

When a new screen or component is required but is not explicitly documented, derive it from:

- the black/charcoal surface hierarchy,
- compact density,
- Roboto typography,
- 4–6px radius language,
- subtle 1px borders,
- blue major-selection state,
- neutral minor-selection state,
- fixed professional tool rails,
- image/content-first composition,
- short direct motion,
- limited elevation,
- open panel construction,
- and the interaction rules defined above.

Do not introduce a new design pattern merely because the new component was not explicitly specified.

The finished application should feel as though every screen was designed by the same professional creative-software team, using one deliberate, tightly controlled mobile editing system.
