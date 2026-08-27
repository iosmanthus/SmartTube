{ pkgs ? import <nixpkgs> {
    config = { android_sdk.accept_license = true; allowUnfree = true; };
  }
}:
let
  sdk = (pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "34" "27" ];
    buildToolsVersions = [ "30.0.3" "34.0.0" ];
    includeNDK = false;
    includeEmulator = false;
    includeSystemImages = false;
  }).androidsdk;
in
pkgs.mkShell {
  buildInputs = [ sdk pkgs.jdk17 pkgs.unzip ];
  ANDROID_HOME = "${sdk}/libexec/android-sdk";
  ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
  JAVA_HOME = pkgs.jdk17;
  GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/libexec/android-sdk/build-tools/34.0.0/aapt2";
}
