# Platform checklist (API 37)
Re-run this list whenever Android publishes behaviour changes for the next API level.

| Item | Rule | How it is checked |
|---|---|---|
| Targeting | targetSdk 37, compileSdk 37 | build files |
| Edge to edge | cannot be opted out of; every screen draws behind the bars and pads with insets | phone: gesture navigation, three button navigation, landscape, a cutout emulator profile |
| Predictive back | `enableOnBackInvokedCallback="true"` on the application; the back order table in W16 is the contract | PlatformRulesTest (manifest and BackHandler list), phone back checks |
| Orientation and resizing | no `screenOrientation`, no `resizeableActivity=false`, no aspect ratio limit | PlatformRulesTest |
| Large screens | no crash, nothing unusable at 600 dp and wider (letterboxed is fine) | one run on a tablet emulator profile per API level |
| Notifications | asked once at the first export, Android 13 and later | NotificationRule test, phone |
| Photo access | the media permission is asked once at first launch; All files access is a separate, explained step | MediaAccess tests, phone |
| Scheduled backup | a `JobService` bound by the system (`BIND_JOB_SERVICE`), not exported, no foreground service; the daily job is re-armed at every start (jobs are not persisted across a reboot) | PlatformRulesTest (service rules), phone |
| Foreground service | types dataSync and mediaProcessing declared (export: both; card import: dataSync only); the app keeps working when the notification is hidden | phone |

## Back order (innermost first)
BackHandler stays; predictive back is on for the application, so the system shows its back-to-home animation only where no handler is enabled. Handlers work by registration order: the newest, innermost enabled one wins. A change to any handler updates this table and `BackInventory.EXPECTED` (app/src/test/kotlin/app/rawline/platform/GuardRules.kt) in the same commit; `PlatformRulesTest.everyBackHandlerInTheAppIsInTheInventory` fails until both agree.

| Screen | A back press does, in this order |
|---|---|
| Editor with the masking tray | 1 a busy, picking, renaming or edit page of the tray steps back one page (MaskTray); 2 the open panel: a crop is cancelled to its start, the curve page returns to Basic, otherwise the panel closes (EditorScreen); 3 leave: save the edit, then back to the viewer (EditorHost) |
| Loupe (viewer) | 1 close the info panel or the star picker; 2 back to the library |
| Library | 1 clear the selection; 2 back to Android home (the system back-to-home animation shows) |
| Studio canvas | 1 close export; 2 close the layers panel; 3 save and leave to the Studio home; Studio home goes back to Develop |

The editor leaves with a visible spinner only if the write takes longer than 150 ms, and a failed write keeps the editor open so Back can try again (existing behaviour, keep).

## Handlers in the code
| File | What it closes |
|---|---|
| `app/EditorHost.kt` | leave the editor: save the recipe, then back to the viewer (always enabled, the outermost) |
| `feature/editor/EditorScreen.kt` | the open panel (crop cancelled first, curve page back to Basic, then the panel closes) |
| `feature/masking/MaskTray.kt` | one page back inside the masking tray |
| `feature/loupe/LoupeScreen.kt` | the info panel or the star picker |
| `feature/library/LibraryScreen.kt` | the selection |
| `feature/studio/CanvasScreen.kt` | export, then the layers panel, then save and leave |
| `feature/studio/StudioRoot.kt` | Studio home back to Develop |

## Library state across a cold start
Filter and sort (preference `libraryFilter`, JSON), column count (`columns`, 2 to 8) and the photo at the top (`libraryTop`, written when the app pauses) are stored. Source and folder were already stored. Changing the source resets the filter and keeps the sort.
