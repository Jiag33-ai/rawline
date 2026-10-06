# Copy guide (docs/COPY.md)

Who reads this: anyone who writes a word the app shows. The mechanical rules are enforced by `CopyRulesTest` (core/model), so a violation fails the build and CI.

1. Australian English. colour, grey, centre, catalogue, organise, optimise, customise, favourite, behaviour, analyse. Licence is the noun, license the verb. The checker lists the US spellings it rejects in `CopyRules`.
2. No em dashes. Use a full stop or a comma. A spaced en dash is also rejected; a range such as 3 to 5 is written with "to".
3. No exclamation marks.
4. Sentence case for titles and buttons: only the first word and proper names start with a capital ("Edit your RAW photos on your phone"). Proper names that keep capitals are listed in `CopyRules.PROPER`; Android's own setting names are in `CopyRules.PHRASES` ("All files access").
5. A button starts with a verb ("Allow photos", "Open settings"). "Not now" is the one allowed exception. A new verb goes on `CopyRules.BUTTON_FIRST_WORDS` in the same pull request.
6. Help items are under 140 characters, one idea each, in plain words. A term the reader may not know gets a glossary line (`help_gloss_*`).
7. Say what happened and what to try ("Not enough space. Free about 300 MB and try again."). Never blame the reader.
8. Numbers carry units (300 MB, 12 MP, 5 s). A count uses the right noun: "1 photo", "2 photos" (`Plurals` in core/ui), never "1 photos" or "photo(s)"; the checker rejects both.
9. No AI model or vendor names. Panasonic and Samsung are allowed because the reader owns those cameras.
10. Resource names say the kind: `title_`, `body_`, `button_`, `action_`, `link_`, `choice_`, `note_`, `error_`, `help_`, `help_gloss_`. The kind selects which rules apply.

## What the check reads
- Every `strings*.xml` in the repository (resources, string arrays), by the kind in the name.
- Kotlin literals in user facing code: `Text("...")`, `text =`, `label =`, `title =`, `subtitle =`, `contentDescription =`, `placeholder =`, `message =`, `hint =` and `Toast.makeText`. A literal passed another way (a variable, a `when` branch returning text, a template with nested quotes) is not seen, so prefer resources for new text. The scanner fails if it finds fewer than 80 strings, so it cannot go quietly blind.
- The documents in `docs/*.md` (outside code fences) for em dashes.
- Not read: folders named `test`, `androidTest`, `tools`, `docs` (for Kotlin), `build`.

Review checklist for a pull request that adds text: the strings are in a `strings_*.xml` (or are literals the scanner reads); `./gradlew :core:model:testDebugUnitTest` passes; the text fits at font scale 1.3 and in TalkBack reads sensibly; `docs/copy-allow.txt` has an entry (text, tab, reason) for anything the rules flag on purpose.

Exceptions live in `docs/copy-allow.txt`, one per line: the exact text, a tab, and the reason. An entry without a reason makes the test fail.

## First run and help (BK-392 to 395)
- The welcome screens, help sheets, glossary lines and the 14 messages are the resources `strings_onboarding.xml`, `strings_help.xml`, `strings_glossary.xml` and `strings_messages.xml` in `core/ui/src/main/res/values`. The wording is the deck as logged; the tests protect the rules, not the sentences, so Jai can change a word.
- The Studio screen of the welcome flow is in `app/src/studioOn/res/values/strings_studio_onboarding.xml`, so a build without Studio carries none of its words.
