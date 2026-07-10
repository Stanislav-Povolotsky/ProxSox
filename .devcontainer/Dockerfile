# ── Base ────────────────────────────────────────────────────────────────────
FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive

WORKDIR /workspace

# ── System packages ────────────────────────────────────────────────────────
# build-essential  — gcc / g++ / make (needed by native Node modules & Rust)
# pkg-config       — required by many Rust -sys crates
# libssl-dev       — OpenSSL headers (jsonwebtoken, reqwest, etc.)
# clang / llvm     — faster C/C++ compilation, needed by some Rust crates
# cmake            — some native build scripts
# protobuf-compiler — in case protobuf support is added later
# lsb-release, ca-certificates, gnupg — for adding external repos
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        pkg-config \
        libssl-dev \
        clang clang-format \
        llvm \
        cmake \
        curl \
        wget \
        git git-lfs \
        make \
        ninja-build \
        ca-certificates \
        gnupg \
        lsb-release \
        unzip \
        jq \
        sudo \
        locales \
        # Python
        python3 \
        python3-pip \
        python3-venv \
        pipx \
        # Misc dev utilities
        bash-completion \
        ripgrep \
        fd-find \
        unzip \
        xz-utils \
        less \
    && locale-gen en_US.UTF-8
RUN pipx install cmakelang
ENV LANG=en_US.UTF-8

# ── Rust (via rustup) ──────────────────────────────────────────────────────
ENV RUSTUP_HOME=/usr/local/rustup \
    CARGO_HOME=/usr/local/cargo \
    PATH="/usr/local/cargo/bin:${PATH}"

RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
        | sh -s -- -y --default-toolchain stable --profile default \
    && rustup component add \
        clippy \
        rustfmt \
        rust-analyzer \
    && cargo --version && rustc --version
# Let the non-root user write to cargo/rustup caches
RUN chmod -R a+w $RUSTUP_HOME $CARGO_HOME

# ── Node.js 22 LTS (via NodeSource) ───────────────────────────────────────
RUN apt-get update \
     && apt-get install -y curl ca-certificates gnupg \
     && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
     && apt-get install -y nodejs \
     && corepack enable \
     && corepack prepare npm@latest --activate \
     && corepack prepare pnpm@latest --activate \
     && corepack prepare yarn@stable --activate \
     && node --version \
     && npm --version

# ── Python requirements ───────────────────────────────────────────────────
COPY .devcontainer/requirements.txt ./.devcontainer/
RUN pip3 install --break-system-packages -r .devcontainer/requirements.txt

# ── Docker CLI ───────────────────────────────

RUN apt install -y ca-certificates curl gnupg && \
    install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
    gpg --dearmor -o /etc/apt/keyrings/docker.gpg && \
    chmod a+r /etc/apt/keyrings/docker.gpg && \
    echo \
      "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
      https://download.docker.com/linux/ubuntu \
      $(. /etc/os-release && echo $VERSION_CODENAME) stable" | \
    tee /etc/apt/sources.list.d/docker.list > /dev/null && \
    apt-get update && \
    apt-get install -y \
        # Docker CLI
        docker-ce-cli

ARG DEBIAN_FRONTEND=noninteractive

# ---------- System packages ----------
RUN apt-get install -y --no-install-recommends \
        openjdk-17-jdk-headless \
        wget \
        unzip \
        curl \
        git \
        make \
        ninja-build \
        xz-utils \
        libglu1-mesa \
        clang \
        cmake \
        pkg-config \
        && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# ---------- Android SDK ----------
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    cd /tmp && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
         -O cmdline-tools.zip && \
    unzip -q cmdline-tools.zip && \
    mv cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm cmdline-tools.zip

# Accept licenses & install SDK components
RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager --update && \
    sdkmanager \
        "platforms;android-35" \
        "build-tools;35.0.0" \
        "platform-tools" \
        "ndk;27.2.12479018" \
        "cmake;3.22.1" \
        "platforms;android-34" \
        "build-tools;30.0.3"

ENV ANDROID_NDK_HOME=${ANDROID_HOME}/ndk/27.2.12479018

# ---------- Flutter SDK ----------
ARG FLUTTER_VERSION=3.27.3
ENV FLUTTER_HOME=/opt/flutter
ENV PATH="${FLUTTER_HOME}/bin:${FLUTTER_HOME}/bin/cache/dart-sdk/bin:${PATH}"

RUN cd /opt && \
    git clone --depth 1 --branch ${FLUTTER_VERSION} \
        https://github.com/flutter/flutter.git && \
    flutter precache --android && \
    flutter config --no-analytics && \
    dart --disable-analytics && \
    yes | flutter doctor --android-licenses 2>/dev/null || true

# Verify installation
RUN flutter doctor -v

# ---------- Golang + gomobile ----------

#RUN apt-get update && apt-get install -y golang-go
ENV GOPATH="/opt/go"
RUN curl -L -o /tmp/go.tar.gz "https://dl.google.com/go/$(curl -s https://go.dev/VERSION\?m\=text | head -1).linux-amd64.tar.gz" && \
    tar -C /usr/local -xzf /tmp/go.tar.gz && \
    rm /tmp/go.tar.gz && \
    mkdir -p ${GOPATH} && \
    chmod 0777 ${GOPATH}
ENV PATH="${PATH}:/usr/local/go/bin:${GOPATH}/bin"

RUN go install golang.org/x/mobile/cmd/gomobile@latest && \
    go install golang.org/x/mobile/cmd/gobind@latest && \
    gomobile init && \
    chmod -R 0777 ${GOPATH}

# ── Non-root user (matches VS Code default) ───────────────────────────────
ARG USERNAME=vscode
ARG USER_UID=2000
ARG USER_GID=$USER_UID

RUN groupadd --gid $USER_GID $USERNAME \
    && useradd --uid $USER_UID --gid $USER_GID -m $USERNAME -s /bin/bash \
    && echo "$USERNAME ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers.d/$USERNAME \
    && chmod 0440 /etc/sudoers.d/$USERNAME \
    && chmod 0755 /root \
    && groupadd -g 1001 docker \
    && usermod -a -G docker vscode

USER $USERNAME
