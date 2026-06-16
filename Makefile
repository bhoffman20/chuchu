.PHONY: build app

build:
	cd zig-src && JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ANDROID_HOME="/home/woof/Android/Sdk" zig build -Doptimize=ReleaseSmall jni

app:
	cd android && JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ANDROID_HOME="/home/woof/Android/Sdk" ./gradlew assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk ../build/

install:
	cd android && JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ANDROID_HOME="/home/woof/Android/Sdk" ./gradlew installDebug
	adb shell am start -n com.jossephus.chuchu/.MainActivity

fmt:
	JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ANDROID_HOME="/home/woof/Android/Sdk" ktfmt --kotlinlang-style $(shell find $(KT_SRC) -name '*.kt')

lint:
	JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64" ANDROID_HOME="/home/woof/Android/Sdk" ktlint --editorconfig=android/.editorconfig "android/app/src/**/*.kt" "android/**/*.gradle.kts"

