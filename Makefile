.PHONY: build app

build:
	cd zig-src && zig build -Doptimize=ReleaseSmall jni

app:
	cd android && ./gradlew --no-daemon assembleDebug

install:
	cd android && ./gradlew --no-daemon installDebug
	adb shell am start -n com.jossephus.chuchu/.MainActivity

fmt:
	ktfmt --kotlinlang-style $(shell find $(KT_SRC) -name '*.kt')

lint:
	ktlint --editorconfig=android/.editorconfig "android/app/src/**/*.kt" "android/**/*.gradle.kts"

