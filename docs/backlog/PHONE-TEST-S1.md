# Phone test: Studio, first release (10 minutes)

For Jai, on the S24 Ultra, with the release APK from CI (it has the Develop | Studio switch). Plain steps. After each step note "ok" or what happened. Do the steps in order; the whole thing is about 10 minutes. If something crashes, note which step and carry on with the next one.

Before you start: open Rawline once and note the version number from Settings (bottom of the screen). Have one JPEG photo and, if you can, one RW2 file and one Samsung Expert RAW (DNG) on the phone.

## 1. First tap of Studio (1 minute)
1. Open Rawline. At the top of the library you should see a switch with Develop and Studio.
2. Tap Studio.
Expected: the Studio home opens within about a second, with a clear "no projects yet" message and buttons to start one. No crash, no blank screen.
3. Tap Develop. Expected: you are back on the library, in the same place you left it. Tap Studio again, then Develop again. Expected: the same each time.
Report if: it takes longer than 2 seconds, the screen goes blank or black, or Develop has lost its place.

## 2. A blank project (2 minutes)
1. In Studio tap the new blank project button and pick the first size.
2. Draw a few strokes with one finger. Pinch with two fingers to zoom and drag to move around. Tap undo twice, then redo once.
3. Add a layer (layers button, then the plus). Draw on it in a different colour. Hide the first layer with its eye.
4. Tap Close (the X, top left).
Expected: a short spinner, then the Studio home with your project in the list and a small picture of it. Closing should take no more than 3 seconds.
5. Tap the project. Expected: it opens exactly as you left it, both layers there.
Report if: strokes are late or jumpy, the brush stops drawing, undo does the wrong thing, or the project is missing or empty.

## 3. The pen (1 minute)
1. In the project use the S Pen: write your name slowly, then fast.
2. Now rest the side of your hand on the glass first, then write with the pen.
Expected: the pen draws, your hand does not. Press harder and the line gets thicker.
Report which of these happened: the palm drew lines; the pen did nothing while the hand was down; both fine. (A known problem: the palm may win. Say what you saw.)

## 4. A photo and an export (2 minutes)
1. Close the project. Tap new project from a photo and pick a JPEG.
2. Draw a few strokes on it. Open the layers panel, tap Add photo and add a second picture. Change the top layer's opacity with the slider.
3. Open the menu (three dots) and choose Export, JPEG, then export.
Expected: a progress bar, then a message that it finished. Open the Gallery: the picture is in Pictures/Rawline with the drawing on it and the right way up.
Report if: the export fails, the picture is black or the wrong way up, or the colours look different from the screen.

## 5. A RAW file (1 minute)
1. Close the project. Tap new project from a photo.
2. Look for your RW2 file in the picker. Then look for your Samsung Expert RAW (DNG).
Expected today: the RW2 is not offered or gives "Could not read that picture". A DNG may open, but it will have been rendered by Android, so its colours may not match how Develop shows it.
Report: what the picker offered for each file, and whether the DNG looked the same as in Develop. (RAW files are meant to come in from Develop later.)

## 6. The Back button (1 minute)
1. Open a project, open the layers panel. Press Back: the panel closes. Press Back again: the project saves and you are on the Studio home.
2. Press Back on the Studio home: you go to Develop (not out of the app).
3. Press Back in the Develop library: you leave to the Android home screen.
Expected: exactly that. Report anything else (for example the app closing too early or Back doing nothing).

## 7. Killing the app (1 minute)
1. Open a project and draw one clear stroke. Wait 6 seconds. Press the Home button.
2. Open the recent apps screen and swipe Rawline away.
3. Open Rawline again.
Expected: the app opens (in Studio, where you left it) on the project list, not on the canvas; your project is in the list; open it and the stroke is there.
Report if: the project is missing, the stroke is missing, or you see a message about damage or recovery. (A line "Recovered from autosave" is fine; say if you saw it.)

## 8. Turning the phone (30 seconds)
1. Open a project, zoom in on the canvas, then turn the phone to landscape and back.
Expected: the drawing stays, nothing flashes black, and ideally the zoom stays.
Report if: the canvas goes blank for more than a second, the zoom jumps back, or the project closes.

## 9. Optional, only if you have a minute: switching while drawing
1. In Develop start an export of three photos. Switch to Studio, open a project and start drawing.
2. When the export notification arrives, pull it down and tap it while you are drawing.
Expected: Rawline shows the Develop queue. Switch back to Studio and open the project: everything you drew before that stroke is there. The stroke you were in the middle of may be missing.
Report if: anything else is lost, or the app crashes.

## 10. Optional, only if the phone is nearly full (under 500 MB free)
1. Create a project from a photo and draw for a few minutes. Watch the top of the canvas.
Expected: a clear single message that the project is not saved because the phone is nearly full; nothing flashing every few seconds.
Report: what the message said and how often it appeared; whether the phone got warm.
(If your phone has plenty of space, skip this: do not fill it on purpose.)

## 11. Send the report (1 minute)
Settings, then Copy report. Paste it into the chat. It has a Studio section with the timers (frame time, commit time, export time); those are the only way to say anything about speed.

## What I will do with it
Every "report if" becomes a numbered fix with a test. The step 3 pen result decides whether the palm fix (BK-481) goes first. A crash in step 1 or 2 stops the release of the Studio switch.
