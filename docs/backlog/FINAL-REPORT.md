# Report for Jai

Written at 12:40Z on 6 Oct 2026, with main at 8d45621. The PM refreshes the numbers marked in the box at the bottom before sending.

## Done
- Studio is built on main up to its first usable version: a project home, the Develop and Studio switch, a canvas with brush, eraser, move and scale, layers, undo, and export to JPEG or PNG.
- It has been checked by over 100 automatic tests and by drawing on a simulated graphics chip. It has not been tried on your phone yet.
- The morning's audit fixes are in as well: engine correctness, memory and export safety, backup restore, faster indexing, and the lint check that can no longer fail quietly.
- I wrote up 507 problems and ideas, ranked them, and made 16 ready-to-use task files, each with the decisions already made and the code that was run on a computer.
- I wrote the order to do them in (DISPATCH), a 10 minute phone test for Studio (PHONE-TEST-S1), and a checklist for the end (FINAL).
- I found three Studio problems before you did: a long brush stroke can run out of memory and be lost, a resting palm can beat the S Pen, and a nearly full phone makes Studio retry saving every few seconds forever. Fixes are written and queued first.

## You need to do
- Run PHONE-TEST-S1 on the release you have now (about 10 minutes) and paste the Copy report.
- Later, take three Samsung Expert RAW photos (day, night, and a 50 MP one) so we can tell whether Rawline can open them.
- Say yes or no to the CLAUDE.md lines in FINAL.
- After the commit check lands, run `git config core.hooksPath tools/hooks` on your copy of the project.
- Nothing else needs you right now.

## Still to do
- In this order, one at a time, about 64 hours in all:
  - the commit message check
  - the Studio fixes (memory, palm, full phone)
  - back button and screen rules
  - a grid that does not jump, and RAW photos first
  - Studio reopening your last project
  - colour tests, then the colour fix for highlights and white balance
  - automatic backups outside the app
  - card import from your S5IIX
  - shader fixes
  - first-run help
  - Studio selections and masks
  - the lint baseline
- Studio will not be shown to anyone in a release until the Studio fixes land.

## Issues
- Nothing in the plan has run on your phone yet. Any claim about speed or looks waits for a Copy report.
- The current release has Studio switched on, with the three problems above. It stays that way until the fixes are in; the next release will not show Studio before then.
- 45 older commits name an AI model in their message, which breaks the project rule. We will not rewrite history. The new commit check stops it from here on.
- A Samsung Expert RAW file might not open at all, depending on how Samsung compresses it. We do not know until you try three files.
- Studio's colour bands for the HSL tool do not match the colours your eye sees (green is partly in the wrong band). Noted for a later colour version.

---
Before sending (PM): 1. run `docs/backlog/tools/final-check.sh` or the `docs/backlog/tools/final-check.sh` and replace "over 100 tests" with the printed counts; 2. check `git log --oneline` for anything merged after 8d45621 and move it from Still to do to Done; 3. confirm which release version name is current and add it as the first line (CLAUDE.md asks for it); 4. delete this box.
