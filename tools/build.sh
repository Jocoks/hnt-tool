#!/bin/bash
# 构建 "HNT 工具"（com.jocoks.hnt）：荣耀主题包 .hnt 拆包 / 打包。
# 产物：/storage/emulated/0/Download/dsh/HNTTool-1.0-unsigned.apk（未签名，用户自己签）
set -e
ROOT=/root/duihua/hnt
APP=$ROOT/app
OUT=$ROOT/build
DIST=/storage/emulated/0/Download/dsh
BT=/usr/lib/android-sdk/build-tools/29.0.3
D8=/root/build/sdk/build-tools/36.0.0/lib/d8.jar
PLATFORM=/root/build/sdk/platforms/android-30/android.jar
MIN=26
TGT=36

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/kcls" "$OUT/kdex" "$OUT/dex" "$OUT/assets"

echo "== 1/6 资源与 assets"
cp -a "$APP/assets/." "$OUT/assets/"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link -I "$PLATFORM" --manifest "$APP/AndroidManifest.xml" \
  -R "$OUT/res.zip" --java "$OUT/gen" --auto-add-overlay \
  --min-sdk-version "$MIN" --target-sdk-version "$TGT" \
  -A "$OUT/assets" -o "$OUT/base.apk"

echo "== 2/6 javac（Xposed API 只做编译期占位，不打进 dex）"
mkdir -p "$OUT/xapi"
javac --release 8 -nowarn -classpath "$PLATFORM" -d "$OUT/xapi" \
  $(find "$ROOT/xapi" -name '*.java')
javac --release 8 -nowarn -classpath "$PLATFORM:$OUT/xapi" -d "$OUT/classes" \
  $(find "$APP/java" "$OUT/gen" -name '*.java')

echo "== 3/6 d8"
java -Xmx2g -cp "$D8" com.android.tools.r8.D8 --release --min-api "$MIN" \
  --lib "$PLATFORM" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "== 4/6 打包"
cd "$OUT"
cp base.apk unsigned.apk
zip -q -0 -j unsigned.apk dex/classes.dex

echo "== 5/6 对齐"
mkdir -p "$DIST"
"$BT/zipalign" -p -f 4 unsigned.apk "$DIST/HNTTool-1.0-unsigned.apk"
"$BT/zipalign" -c 4 "$DIST/HNTTool-1.0-unsigned.apk"
ls -l "$DIST/HNTTool-1.0-unsigned.apk"
sha256sum "$DIST/HNTTool-1.0-unsigned.apk"
