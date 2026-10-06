#!/bin/bash
# FINAL.md step 1: re-run every check the campaign wrote, against the LIVE tree. Read only (builds into a scratch folder). Prints one line per check, exit 1 if any failed.
R=${1:-/home/user/rawline}
SC=${SC:-/path/to/session/scratchpad}   # set SC to the folder that holds the kotlin helper scripts of the original session
OUT=$SC/final; mkdir -p $OUT
L=/root/.gradle/wrapper/dists/gradle-9.8.0-bin/3m7h6ceboy5k31n8kzwzuxssm/gradle-9.8.0/lib
fail=0
say() { printf "%-5s %s\n" "$1" "$2"; if [ "$1" = FAIL ]; then fail=1; fi; return 0; }
run_jar() { # jar resources-dir classes...
  local jar=$1 res=$2; shift 2
  JAVA_TOOL_OPTIONS="-Drepo.root=$R $EXTRA_PROPS" sh $SC/kt/run.sh $jar $res "$@" 2>&1 | tail -3 | tr '\n' ' '
}
# 1 studio-model: live main and test sources, with the test resources on the classpath
M=$R/core/studio-model/src
sh $SC/kt/kc.sh $OUT/studio.jar $(find $M/main -name '*.kt') $(find $M/test -name '*.kt') >/dev/null 2>$OUT/studio.err
CL=$(unzip -Z1 $OUT/studio.jar | grep 'Test.class$' | grep -v '\$' | sed 's/.class//;s#/#.#g' | tr '\n' ' ')
J=/root/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar
H=/root/.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/42a25dc3219429f0e5d060061f71acb49bf010a0/hamcrest-core-1.3.jar
JS=/root/.gradle/caches/modules-2/files-2.1/org.json/json/20250517/d67181bbd819ccceb929b580a4e2fcb0c8b17cd8/json-20250517.jar
r=$(cd $R && java -cp $OUT/studio.jar:$M/test/resources:$L/kotlin-stdlib-2.4.10.jar:$J:$H:$JS org.junit.runner.JUnitCore $CL 2>&1 | tail -3 | tr '\n' ' ')
case "$r" in *"OK ("*) say ok "studio-model host tests: $r";; *) say FAIL "studio-model host tests: $r $(head -2 $OUT/studio.err | tr '\n' ' ')";; esac
# 2 core/model live tests
M=$R/core/model/src
sh $SC/kt/kc.sh $OUT/model.jar $(find $M/main -name '*.kt') $(find $M/test -name '*.kt') >/dev/null 2>$OUT/model.err
CL=$(unzip -Z1 $OUT/model.jar | grep 'Test.class$' | grep -v '\$' | sed 's/.class//;s#/#.#g' | tr '\n' ' ')
r=$(cd $R && java -cp $OUT/model.jar:$L/kotlin-stdlib-2.4.10.jar:$J:$H:$JS org.junit.runner.JUnitCore $CL 2>&1 | tail -3 | tr '\n' ' ')
case "$r" in *"OK ("*) say ok "core/model host tests: $r";; *) say FAIL "core/model host tests: $r $(head -2 $OUT/model.err | tr '\n' ' ')";; esac
# 3 copy rules over the live tree (W27 checker, the version in the audit folder)
r=$(JAVA_TOOL_OPTIONS="-Drepo.root=$R" sh $SC/kt/run.sh $SC/ob/kt/ob.jar app.rawline.core.model.copy.CopyRulesTest 2>&1 | grep -E "^[a-zA-Z].*\[|OK \(|Tests run" | head -8 | tr '\n' ';')
case "$r" in *"OK ("*) say ok "copy rules over the repository: $r";; *) say FAIL "copy rules over the repository: $r";; esac
# 4 platform rules over the live tree (W16 guards: manifest and BackHandler list)
r=$(JAVA_TOOL_OPTIONS="-Drepo.root=$R" sh $SC/kt/run.sh $SC/w16/kt/w16.jar app.rawline.platform.PlatformRulesTest 2>&1 | grep -E "^[0-9]\)|OK \(|Tests run" | head -4 | tr '\n' ';')
case "$r" in *"OK ("*) say ok "platform rules: $r";; *) say FAIL "platform rules (red until W16 has merged): $r";; esac
# 5 every patch still applies (only meaningful for work not yet merged)
for p in w22/final/look2_engine.patch w22/final/editrecipe.patch w23/look2_shader.patch w23/look2_shader_after_w22.patch s1bfix/model.patch s1bfix/native.patch w29/library.patch w32/patches/w32-core.patch w32/patches/w32-ui.patch w32/patches/w09b-after-w22.patch; do
  if (cd $R && git apply --check $SC/$p 2>/dev/null); then say ok "applies: $p"; else say info "does not apply (merged already, or the tree moved): $p"; fi
done
# 6 em dashes and model names in tracked text and in commit messages
d=$(cd $R && git ls-files '*.md' '*.kt' '*.kts' '*.xml' '*.sh' '*.py' '*.cpp' '*.h' '*.glsl' '*.frag' | grep -v -e '^\.android-sdk' | xargs grep -l $'\xe2\x80\x94' 2>/dev/null | head -5 | tr '\n' ' ')
[ -z "$d" ] && say ok "no em dash in tracked text" || say FAIL "em dash in: $d"
# the patterns are assembled from fragments so this script does not carry the names it looks for
PAT="(so""nnet|ha""iku|\bop""us\b|cla""ude|anthro""pic|\bg""pt|open""ai|gem""ini|gem""ma|co""pilot|ll""ama|mis""tral|co""dex)"
ALLOW="s#cla""ude\.ai/code/session_[A-Za-z0-9]+##g; s#CLA""UDE\.md##g; s#\.cla""ude##g; s#Cla""ude-Session##g; s#Cla""ude Code##g; s#cla""ude-0##g"
m=$(cd $R && git log 0620e2a..HEAD --format=%B | sed -E "$ALLOW" | grep -i -E "$PAT" | head -3 | tr '\n' ' ')
old=$(cd $R && git log --format=%h | while read h; do git log -1 --format=%B $h | sed -E "$ALLOW" | grep -q -i -E "$PAT" && echo $h; done | wc -l); say info "$old commits in all of history carry a model name in a message (all up to 0620e2a, before the Studio work; history is not rewritten, see BK-501)"
[ -z "$m" ] && say ok "no model name in commit messages since 0620e2a" || say FAIL "model name in commit messages: $m"
t=$(cd $R && git ls-files | grep -v -e '^\.android-sdk' -e '\.png$' -e '\.jar$' -e '\.rgba$' -e '^CLA''UDE\.md$' -e '\.apk$' -e '^docs/UI_SPEC\.md$' -e 'tools/commit-msg-check.sh' -e 'tools/test-commit-msg-check.sh' -e 'tools/check-commit-messages.sh' -e 'tools/final-check.sh' -e '^docs/backlog/' | while read f; do sed -E "$ALLOW" "$f" 2>/dev/null | grep -q -i -E "$PAT" && echo $f; done | head -5 | tr '\n' ' ')
u=$(cd $R && sed -E "$ALLOW" docs/UI_SPEC.md 2>/dev/null | grep -c -i -E "$PAT"); [ "$u" != 0 ] && say info "docs/UI_SPEC.md carries a vendor name in its title line ($u lines): rename the heading, a one line edit for a worker (not mine to edit)"
[ -z "$t" ] && say ok "no model or vendor name in tracked files (the committed docs/backlog copy is scanned separately below)" || say FAIL "model or vendor name in tracked files: $t"
# 6b doc scan: the committed docs/backlog copy, this session's audit folder (text, parts, scripts) and the staged copy, same rule: no AI model or vendor names (allowed: the file name CLAUDE.md, the .claude folder, the Claude-Session trailer, the words Claude Code)
A=$SC/audit
for set in "committed docs/backlog:$(cd $R && git ls-files docs/backlog | sed "s#^#$R/#")" "audit folder:$(find $A -type f \( -name '*.md' -o -name '*.py' -o -name '*.sh' -o -name '*.txt' \) -not -path '*/docs-backlog/*' -not -path '*/fixtures-dng/*')" "staged docs/backlog:$(find $A/docs-backlog -type f \( -name '*.md' -o -name '*.py' -o -name '*.sh' -o -name '*.txt' \) 2>/dev/null)"; do
  label=${set%%:*}; files=${set#*:}; bad=""
  for f in $files; do [ -f "$f" ] && sed -E "$ALLOW" "$f" | grep -q -i -E "$PAT" && bad="$bad ${f#$R/}"; done
  [ -z "$bad" ] && say ok "doc scan, $label: no model or vendor name" || say FAIL "doc scan, $label:$(echo $bad | cut -c1-300)"
done
n=$(cd $R && git status --short | wc -l); b=$(cd $R && git branch --list | wc -l)
say info "working tree has $n changed paths, $b local branches (both should be 0 and 1 at the end)"
exit $fail
