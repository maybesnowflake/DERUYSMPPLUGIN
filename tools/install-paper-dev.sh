#!/usr/bin/env bash
# 서버 녹화(replay) 기능은 Paper 내부 클래스(마인크래프트 원본, Mojang 이름)로 컴파일한다.
# 공식 PaperMC 배포 JAR을 받아(체크섬 고정) 패치만 적용한 뒤, 컴파일에 필요한 클래스만 묶어
# 로컬 Maven 저장소에 com.deruy.dev:paper-server-mojmap 으로 설치한다. 결과물은 플러그인에 포함되지 않는다(provided).
set -euo pipefail
BUILD=132
SHA256=5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba
VERSION="1.21.11-$BUILD"
REPO="${MAVEN_REPO:-$HOME/.m2/repository}"
TARGET="$REPO/com/deruy/dev/paper-server-mojmap/$VERSION/paper-server-mojmap-$VERSION.jar"
if [ -f "$TARGET" ]; then echo "이미 설치됨: $TARGET"; exit 0; fi

WORK="${PAPER_DEV_DIR:-$(mktemp -d)}"
mkdir -p "$WORK" && cd "$WORK"
if [ ! -f paper.jar ] || ! echo "$SHA256  paper.jar" | sha256sum -c --quiet; then
  curl -fsSL -o paper.jar "https://fill-data.papermc.io/v1/objects/$SHA256/paper-1.21.11-$BUILD.jar"
  echo "$SHA256  paper.jar" | sha256sum -c --quiet
fi
[ -f versions/1.21.11/paper-1.21.11.jar ] || java -Dpaperclip.patchonly=true -jar paper.jar >/dev/null

rm -rf merged && mkdir merged && cd merged
unzip -q -o ../versions/1.21.11/paper-1.21.11.jar 'net/*' 'com/mojang/*' 'org/bukkit/craftbukkit/*' 'io/papermc/paper/*' 'ca/spottedleaf/*' 'com/destroystokyo/paper/*' 'org/spigotmc/*'
for lib in $(find ../libraries -name '*.jar' | grep -E '/(netty-(buffer|common|transport|codec-base|handler))-4|/authlib-|/brigadier-|/datafixerupper-|/logging-1|/fastutil-|/jtracy-|/concurrentutil-|/joml-'); do
  unzip -q -o "$lib" -x 'META-INF/*' || true
done
rm -rf META-INF
jar cf "../paper-server-mojmap-$VERSION.jar" .
cd ..
mvn -B -q install:install-file -Dfile="paper-server-mojmap-$VERSION.jar" -DgroupId=com.deruy.dev \
  -DartifactId=paper-server-mojmap -Dversion="$VERSION" -Dpackaging=jar -DgeneratePom=true
echo "설치 완료: $TARGET"
