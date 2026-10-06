#!/bin/bash
# FINAL.md step 1: re-run every check the campaign wrote, against the LIVE tree. Read only (builds into a scratch folder). Prints one line per check, exit 1 if any failed.
R=${1:-/home/user/rawline}
SC=/tmp/claude-0/-home-user-rawline/d3518fef-0638-56af-9eeb-c31b0a0c3dff/scratchpad
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
for p in w22/final/look2_engine.patch w22/final/editrecipe.patch w23/look2_shader.patch w23/look2_shader_after_w22.patch s1bfix/model.patch s1bfix/native.patch w29/library.patch; do
  if (cd $R && git apply --check $SC/$p 2>/dev/null); then say ok "applies: $p"; else say info "does not apply (merged already, or the tree moved): $p"; fi
done
# 6 em dashes and model names in tracked text and in commit messages
d=$(cd $R && git ls-files '*.md' '*.kt' '*.kts' '*.xml' '*.sh' '*.py' '*.cpp' '*.h' '*.glsl' '*.frag' | grep -v -e '^\.android-sdk' | xargs grep -l $'\xe2\x80\x94' 2>/dev/null | head -5 | tr '\n' ' ')
[ -z "$d" ] && say ok "no em dash in tracked text" || say FAIL "em dash in: $d"
m=$(cd $R && git log 0620e2a..HEAD --format=%B | grep -i -E "(sonnet|opus|haiku|gpt-|gemini|anthropic)" | head -3 | tr '\n' ' ')
old=$(cd $R && git log --format=%h --grep=Sonnet | wc -l); say info "$old commits in all of history carry a model name in a trailer (all up to 0620e2a, before the Studio work; history is not rewritten, see BK-501)"
[ -z "$m" ] && say ok "no model name in commit messages since 0620e2a" || say FAIL "model name in commit messages: $m"
t=$(cd $R && git ls-files | grep -v -e '^\.android-sdk' -e '\.png$' -e '\.jar$' -e '\.rgba$' | xargs grep -l -i -E "(sonnet|opus-|claude-[0-9a-z]|gpt-[0-9]|gemini-[0-9])" 2>/dev/null | head -5 | tr '\n' ' ')
[ -z "$t" ] && say ok "no model name in tracked files" || say FAIL "model name in tracked files: $t"
# 7 working tree state
n=$(cd $R && git status --short | wc -l); b=$(cd $R && git branch --list | wc -l)
say info "working tree has $n changed paths, $b local branches (both should be 0 and 1 at the end)"
exit $fail
