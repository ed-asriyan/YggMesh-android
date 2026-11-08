# Everything needed to build YggMesh and to drive Android phones with adb, so
# that the host needs nothing but Docker and VS Code (see docs/device-testing.md).
# x86_64 on every host: Google ships adb, the NDK and the build tools for Linux
# as x86_64 binaries only. On an Apple Silicon Mac this runs under emulation
# (enable "Use Rosetta for x86_64/amd64 emulation" in Docker Desktop: it is a lot faster).
FROM --platform=linux/amd64 mcr.microsoft.com/devcontainers/base:ubuntu-24.04

ARG CMDLINE_TOOLS=11076708
# adb of the container. contrib/host-adb.sh downloads the same version for the
# host: a client and a server of different versions do not work together.
ARG PLATFORM_TOOLS=37.0.1
ARG NDK_VERSION=27.2.12479018

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_NDK_HOME=/opt/android-sdk/ndk/${NDK_VERSION}
ENV PATH="${PATH}:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools"
# The adb server runs on the host (contrib/host-adb.sh), which holds the USB
# phones; adb in the container is only a client of it
ENV ANDROID_ADB_SERVER_ADDRESS=host.docker.internal
ENV ANDROID_ADB_SERVER_PORT=5037

RUN apt-get update \
 && apt-get install -y --no-install-recommends openjdk-17-jdk-headless unzip curl ca-certificates build-essential pkg-config \
 && rm -rf /var/lib/apt/lists/*

# Android SDK: platform and build tools of app/build.gradle, and the NDK that
# contrib/build-yggdrasil-ng.sh builds with. adb is a pinned platform-tools.
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
 && curl -fsSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
 && unzip -q /tmp/clt.zip -d /tmp/clt \
 && mv /tmp/clt/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
 && rm -rf /tmp/clt /tmp/clt.zip \
 && (yes | sdkmanager --licenses > /dev/null || true) \
 && sdkmanager "platforms;android-34" "build-tools;34.0.0" "ndk;${NDK_VERSION}" \
 && curl -fsSL -o /tmp/pt.zip https://dl.google.com/android/repository/platform-tools_r${PLATFORM_TOOLS}-linux.zip \
 && unzip -q /tmp/pt.zip -d ${ANDROID_HOME} \
 && rm /tmp/pt.zip \
 && chown -R vscode:vscode ${ANDROID_HOME}

USER vscode

# Rust with the Android targets, and cargo-ndk
ENV PATH="/home/vscode/.cargo/bin:${PATH}"
RUN curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain stable \
 && rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android \
 && cargo install cargo-ndk --locked

# Owned by vscode, so the volume mounted here (devcontainer.json) is writable
RUN mkdir -p /home/vscode/.claude
