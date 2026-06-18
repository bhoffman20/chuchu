.PHONY: build app

build:
	cd zig-src && zig build -Doptimize=ReleaseSmall jni

app:
	cd android && ./gradlew assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk ../build/

install:
	cd android && ./gradlew installDebug
	adb shell am start -n com.jossephus.chuchu/.MainActivity

fmt:
	  ktfmt --kotlinlang-style $(shell find $(KT_SRC) -name '*.kt')

lint:
	  ktlint --editorconfig=android/.editorconfig "android/app/src/**/*.kt" "android/**/*.gradle.kts"

