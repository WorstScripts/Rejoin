#!/bin/bash
set -e
unset JAVA_TOOL_OPTIONS
cd "$(dirname "$0")"
AJ=/usr/lib/android-sdk/platforms/android-23/android.jar
APKSIG=/root/build/apksig-classes
rm -rf out && mkdir -p out/classes out/dex
# patch "resizeableActivity" etc: aapt only knows API 23 attributes, so the manifest stays simple
# floating-window code that goes into floating clones -> assets/float.dex
mkdir -p out/float_cls
javac -nowarn -source 8 -target 8 -bootclasspath $AJ -d out/float_cls $(find float_src -name '*.java') 2>&1 | grep -v -E 'warning|^Note' || true
[ -f out/float_cls/com/worst/floatwin/FloatInit.class ] || { echo "float javac failed"; exit 1; }
for c in $(find out/float_cls -name '*.class'); do javap -c -p "$c" | grep -q invokedynamic && { echo "INDY in $c"; exit 1; }; done
dalvik-exchange --dex --min-sdk-version=21 --output=assets/float.dex out/float_cls
mkdir -p out/gen && aapt package -f -M AndroidManifest.xml -S res -A assets -I $AJ -J out/gen -F out/unsigned.apk
javac -nowarn -source 8 -target 8 -bootclasspath $AJ -cp $APKSIG -d out/classes $(find src out/gen -name "*.java") 2>&1 | grep -v -E 'warning|^Note' || true
[ -f out/classes/com/multirejoin/app/MainActivity.class ] || { echo "javac failed"; exit 1; }
# no lambdas allowed (Android has no LambdaMetafactory)
for c in $(find out/classes -name '*.class'); do javap -c -p "$c" | grep -q invokedynamic && { echo "INDY in $c"; exit 1; }; done
dalvik-exchange --dex --min-sdk-version=26 --output=out/dex/classes.dex out/classes $APKSIG
cp out/unsigned.apk out/withdex.apk
(cd out/dex && zip -q ../withdex.apk classes.dex)
zipalign -f -p 4 out/withdex.apk out/aligned.apk
apksigner sign --ks keys/app.p12 --ks-pass pass:multirejoin --ks-key-alias app --out out/MultiRejoin.apk out/aligned.apk
apksigner verify -v out/MultiRejoin.apk | head -5
ls -la out/MultiRejoin.apk
